package com.hualuo.engine.sandbox

import com.hualuo.engine.toolcalls.IndexStubs
import com.hualuo.engine.toolcalls.ProotShimLauncher
import com.hualuo.engine.toolcalls.StubEntry
import com.hualuo.engine.toolcalls.TarStubs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * 沙盒门面全链对照表（离线：索引与 .apk 走注入口，proot 命令走 /bin/sh 桩）。
 * 重点验 D2/D3：假成功路径全部堵死——每个「成功」都能说出证据。
 */
class SandboxManagerTest {

    /** 桩里 apk add 成功后要写进的 installed 表（复验读的就是它）。 */
    private val installed = mutableMapOf("base-pkg" to "0.9-r0")

    private val root = Files.createTempDirectory("hrt-mgr").toFile()
    private val work = Files.createTempDirectory("hrt-work").toFile()

    private fun manager(): SandboxManager = SandboxManager(
        rootfsDir = root,
        workDir = work,
        indexTarGzOverride = { ByteArrayInputStream(IndexStubs.tarGzOfIndex(IndexStubs.INDEX)) },
        apkFileOverride = { name, version, target -> target.writeText("apk-stub-$name-$version") },
        sessionProvider = { rootDir, binds -> ProotSession(rootfsDir = rootDir, bindMounts = binds, launcher = ProotShimLauncher(installed, IndexStubs.INDEX)) },
    )

    private fun installRootfs() {
        val m = manager()
        val tarGz = TarStubs.ofEntries(
            StubEntry("bin/", type = '5'),
            StubEntry("bin/busybox", content = "#!/bin/sh\nstub-busybox"),
            // /bin/sh 是 busybox 的符号链接（真 Alpine 就是这样）：顺便验证 D6 真链接路径
            StubEntry("bin/sh", type = '2', linkName = "/bin/busybox"),
        )
        m.install(ByteArrayInputStream(tarGz))
    }

    @Test
    fun installRootfsMakesEnvironmentReadyAndWritesSnapshot() {
        assertFalse("装之前不该算已装：${manager().statusLine()}", manager().isInstalled)
        val result = installRootfsAndReport()
        assertTrue("装完要出终态：$result", result.contains("终态：rootfs 安装完成"))
        assertTrue("base-world 快照要拍：$result", result.contains("base-world 快照"))
        assertTrue("装完必须已就绪：", manager().isInstalled)
        assertEquals("快照文件要在：", true, File(work, "baseworld.txt").exists())
    }

    private fun installRootfsAndReport(): String = manager().let { m ->
        val tarGz = TarStubs.ofEntries(
            StubEntry("bin/", type = '5'),
            StubEntry("bin/busybox", content = "#!/bin/sh\nstub-busybox"),
            // /bin/sh 是 busybox 的符号链接（真 Alpine 就是这样）：顺便验证 D6 真链接路径
            StubEntry("bin/sh", type = '2', linkName = "/bin/busybox"),
        )
        m.install(ByteArrayInputStream(tarGz))
    }

    @Test
    fun installPackagesVerifiesRealEntryNotJustExitCode() {
        installRootfs()
        val result = manager().installPackages(listOf("hello-pkg"))
        assertTrue("全链安装要成功并复验：$result", result.contains("终态：安装成功 hello-pkg-1.0-r0"))
        assertTrue("installed 表要有这个包：$installed", "hello-pkg" in installed)
    }

    @Test
    fun planEmptyButNotVerifiedIsHonestFailure() {
        installRootfs()
        // 输入是别名 so:xxx：计划为空、复验对不上 -> 必须 PLAN 报错（不许假成功）
        val err = runCatching { manager().installPackages(listOf("so:libmissing.so")) }.exceptionOrNull()
        assertTrue("别名没包装上要诚实报错：err=$err",
            err is SandboxException && err.error.kind == SandboxError.Kind.PLAN)
    }

    @Test
    fun unknownPackageReportsNotSilent() {
        installRootfs()
        val err = runCatching { manager().installPackages(listOf("ghost-pkg")) }.exceptionOrNull()
        assertTrue("查不到的包要报 PLAN：err=$err", err is SandboxException && err.error.kind == SandboxError.Kind.PLAN)
        assertTrue("错误里要点名包：${err?.message}", err!!.message!!.contains("ghost-pkg"))
    }

    @Test
    fun busyGateRejectsConcurrentMutableOps() {
        installRootfs()
        val m = manager()
        val holder = m.sessionForTest().holdForBusyTest(1_500L)
        val err = runCatching { m.installPackages(listOf("hello-pkg")) }.exceptionOrNull()
        assertTrue("busy 必须明确拒绝：err=$err", err is SandboxException && err.error.kind == SandboxError.Kind.BUSY)
        com.hualuo.engine.toolcalls.ProotShimLauncher.Companion.destroyQuietly(holder)
    }

    @Test
    fun packageNameWithInjectionCharsIsRejected() {
        val err = runCatching { manager().installPackages(listOf("pkg; rm -rf /")) }.exceptionOrNull()
        assertTrue("越界包名必须 REJECTED（防注入，不是藏内容）：err=$err",
            err is SandboxException && err.error.kind == SandboxError.Kind.REJECTED)
    }

    @Test
    fun removePackageThenListIsHonest() {
        installRootfs()
        val m = manager()
        m.installPackages(listOf("hello-pkg"))
        val removed = m.removePackage("hello-pkg")
        assertTrue("删除成功要带复验证据：$removed", removed.contains("终态：删除成功"))
        assertFalse("删完 list 里不该再有：${m.listPackages()}", m.listPackages().contains("hello-pkg"))
    }
}
