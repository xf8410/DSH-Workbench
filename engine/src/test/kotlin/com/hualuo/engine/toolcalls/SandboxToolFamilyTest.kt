package com.hualuo.engine.toolcalls

import com.hualuo.engine.sandbox.ProotSession
import com.hualuo.engine.sandbox.SandboxManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * 沙盒工具族对照表：闸门在不在，决定执行类工具存不存在（默认拒执行）。
 * apk info/add/del 走共享桩 [ProotShimLauncher]，D2/D3 复验走真逻辑不是 mock 绿。
 */
class SandboxToolFamilyTest {

    private fun tmpManager(installed: MutableMap<String, String>): SandboxManager {
        val root = Files.createTempDirectory("hrt-mgr").toFile()
        val work = Files.createTempDirectory("hrt-work").toFile()
        File(root, "bin").mkdirs(); File(root, "sbin").mkdirs()
        File(root, "bin/sh").writeText("stub")
        return SandboxManager(
            rootfsDir = root,
            workDir = work,
            indexTarGzOverride = { ByteArrayInputStream(IndexStubs.tarGzOfIndex(IndexStubs.INDEX)) },
            apkFileOverride = { name, version, target -> target.writeText("apk-stub-$name-$version") },
            sessionProvider = { rootDir, binds -> ProotSession(rootfsDir = rootDir, bindMounts = binds, launcher = ProotShimLauncher(installed, IndexStubs.INDEX)) },
        )
    }

    @Test
    fun readonlyToolsExistWithoutGate() {
        val registry = SandboxToolFamily.register(ToolRegistry(), manager = tmpManager(mutableMapOf("base-pkg" to "0.9-r0")))
        val names = registry.specs().map { it.name }
        assertTrue("status 应在：$names", "sandbox_status" in names)
        assertTrue("list_packages 应在：$names", "sandbox_list_packages" in names)
        assertFalse("没闸门时 run_command 不许存在：$names", "sandbox_run_command" in names)
        assertFalse("没闸门时 install 不许存在：$names", "sandbox_install_packages" in names)
        assertFalse("没闸门时 remove 不许存在：$names", "sandbox_remove_package" in names)
    }

    @Test
    fun writeToolsExistOnlyWithGateAndPreviewShowsFullCommand() {
        var asked: SandboxConfirmProposal? = null
        val registry = SandboxToolFamily.register(
            ToolRegistry(),
            manager = tmpManager(mutableMapOf("base-pkg" to "0.9-r0")),
            confirmer = SandboxConfirmer { p -> asked = p; true },
        )
        val names = registry.specs().map { it.name }
        assertTrue("有闸门时 run_command 应在：$names", "sandbox_run_command" in names)
        assertTrue("有闸门时 install 应在：$names", "sandbox_install_packages" in names)

        val outcome = registry.execute("sandbox_run_command", """{"command":"echo ok-in-sandbox"}""")
        assertTrue("执行结果要带终态：${outcome.text}", "终态" in outcome.text)
        assertTrue("确认卡要看到命令全文：", asked!!.preview.contains("echo ok-in-sandbox"))
    }

    @Test
    fun rejectedGateMeansNothingHappened() {
        val registry = SandboxToolFamily.register(
            ToolRegistry(),
            manager = tmpManager(mutableMapOf("base-pkg" to "0.9-r0")),
            confirmer = SandboxConfirmer { false },
        )
        val result = registry.execute("sandbox_run_command", """{"command":"echo should-not-run"}""").text
        assertTrue("拒绝要回「没确认」：$result", result.contains("没有确认"))
        assertFalse("拒绝后不许有执行终态：$result", result.contains("终态：执行"))
    }

    @Test
    fun installViaToolsRunsFullVerifyChain() {
        val registry = SandboxToolFamily.register(
            ToolRegistry(),
            manager = tmpManager(mutableMapOf("base-pkg" to "0.9-r0")),
            confirmer = SandboxConfirmer { true },
        )
        val outcome = registry.execute("sandbox_install_packages", """{"packages":["hello-pkg"]}""")
        assertTrue("全链安装要成功且带复验：${outcome.text}", outcome.ok && outcome.text.contains("终态：安装成功"))
    }
}

/** 索引 tar 桩（与 SandboxManagerTest 共用）。 */
internal object IndexStubs {
    val INDEX = """
        C:Q1
        P:hello-pkg
        V:1.0-r0
    """.trimIndent()

    fun tarGzOfIndex(text: String): ByteArray {
        val raw = java.io.ByteArrayOutputStream()
        val body = text.toByteArray()
        val header = ByteArray(512)
        val name = "APKINDEX".toByteArray()
        System.arraycopy(name, 0, header, 0, name.size)
        header[156] = '0'.code.toByte() // typeflag：普通文件（漏了它条目会被解索引方跳过）
        val sizeOctal = body.size.toString(8).padStart(11, '0') + "\u0000"
        System.arraycopy(sizeOctal.toByteArray(), 0, header, 124, 12)
        // chksum：先按空格填，再算两次（第一次算时按空格）
        for (i in 148..155) header[i] = ' '.code.toByte()
        var sum = 0L
        for (b in header) sum += b.toLong() and 0xFF
        val chk = (java.lang.Long.toOctalString(sum) + " ").padStart(6, '0').toByteArray()
        System.arraycopy(chk, 0, header, 148, 6)
        sum = 0L
        for (b in header) sum += b.toLong() and 0xFF
        val chk2 = (java.lang.Long.toOctalString(sum) + " ").padStart(6, '0').toByteArray()
        System.arraycopy(chk2, 0, header, 148, 6)
        raw.write(header)
        raw.write(body)
        val pad = (512 - body.size % 512) % 512
        if (pad > 0) raw.write(ByteArray(pad))
        raw.write(ByteArray(1024))
        val gz = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(gz).use { it.write(raw.toByteArray()) }
        return gz.toByteArray()
    }
}
