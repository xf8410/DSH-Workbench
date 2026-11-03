package com.hualuo.engine.lsp

import com.hualuo.engine.io.streamingCopy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装器契约（纯 JVM）：成功路径、哈希不符拒收、超限闸、空包、断网、账对不上、
 * 本地命中不碰网、坏本地件自愈、半截残留清理、进度单调——每条都把「不留半截文件」钉到位。
 *
 * 期望哈希用引擎自己的 streamingCopy 算：同一条算法路径，不手搓第二个哈希器。
 */
class PackInstallerTest {

    private fun shaOf(payload: ByteArray): String =
        streamingCopy(ByteArrayInputStream(payload), ByteArrayOutputStream()).sha256Hex

    private fun packOf(payload: ByteArray, url: String = "https://example.com/p.pack"): LanguagePack =
        LanguagePack(
            id = "kotlin",
            displayName = "Kotlin 语言服务",
            version = "1.0.0",
            serverBinary = "kotlin-language-server",
            sha256Hex = shaOf(payload),
            downloadUrl = url,
        )

    private class FakeFetch(
        private val payload: ByteArray,
        private val reportedBytes: Long = -1L,
        private val failWith: Exception? = null,
        private val chunk: Int = 7,
    ) : PackFetch {
        var calls = 0
            private set

        override fun fetch(url: String, sink: OutputStream, onProgress: (Long, Long) -> Unit): Long {
            calls += 1
            failWith?.let { throw it }
            var off = 0
            while (off < payload.size) {
                val n = minOf(chunk, payload.size - off)
                sink.write(payload, off, n)
                off += n
                onProgress(off.toLong(), payload.size.toLong())
            }
            return if (reportedBytes >= 0) reportedBytes else payload.size.toLong()
        }
    }

    private fun tempDir(name: String): File = Files.createTempDirectory(name).toFile()

    @Test
    fun downloadsVerifiesAndRenamesAtomically() {
        val dir = tempDir("pack-happy")
        val payload = "hello pack".toByteArray(Charsets.UTF_8)
        val report = PackInstaller(dir, FakeFetch(payload)).install(packOf(payload))

        assertTrue("走网络装成的就该说下载过", report.downloaded)
        assertEquals(payload.size.toLong(), report.bytes)
        assertEquals(shaOf(payload), report.sha256Hex)
        assertEquals("kotlin-1.0.0.pack", report.file.name)
        assertTrue(payload.contentEquals(report.file.readBytes()))
        assertEquals("成功后不许留任何 .part 残件", 0, dir.listFiles()!!.count { it.name.endsWith(".part") })
    }

    @Test
    fun hashMismatchRejectsAndCleansUp() {
        val dir = tempDir("pack-hash")
        val payload = "tampered".toByteArray(Charsets.UTF_8)
        val pack = packOf(payload).copy(sha256Hex = "0".repeat(64))
        val error = expectReject { PackInstaller(dir, FakeFetch(payload)).install(pack) }

        assertTrue("必须点名哈希不匹配：$error", error.contains("哈希不匹配"))
        assertEquals("拒收后目录里一个文件都不许留", 0, dir.listFiles()!!.size)
    }

    @Test
    fun oversizeIsRejectedBeforeTheCrossingChunkLands() {
        val dir = tempDir("pack-oversize")
        val payload = "0123456789".toByteArray(Charsets.UTF_8)
        val error = expectReject {
            PackInstaller(dir, FakeFetch(payload, chunk = 4), PackLimits(maxBytes = 9)).install(packOf(payload))
        }

        assertTrue("必须点名超过上限：$error", error.contains("超过上限"))
        assertEquals("跨限之后不许有半个字节留下", 0, dir.listFiles()!!.size)
    }

    @Test
    fun emptyDownloadIsRejected() {
        val dir = tempDir("pack-empty")
        val payload = ByteArray(0)
        val error = expectReject { PackInstaller(dir, FakeFetch(payload)).install(packOf(payload)) }

        assertTrue("空包要明确拒：$error", error.contains("0 字节"))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun networkFailureBecomesChineseRejectWithCleanup() {
        val dir = tempDir("pack-net")
        val payload = "x".toByteArray(Charsets.UTF_8)
        val error = expectReject {
            PackInstaller(dir, FakeFetch(payload, failWith = IOException("connection reset"))).install(packOf(payload))
        }

        assertTrue("断网要说人话：$error", error.contains("下载断了"))
        assertTrue("要带重试出路：$error", error.contains("重试"))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun fetchAccountingLieIsCaught() {
        val dir = tempDir("pack-lie")
        val payload = "abc".toByteArray(Charsets.UTF_8)
        val error = expectReject {
            PackInstaller(dir, FakeFetch(payload, reportedBytes = 2)).install(packOf(payload))
        }

        assertTrue("下载器口头账不作数：$error", error.contains("账对不上"))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun localHitSkipsNetworkEntirely() {
        val dir = tempDir("pack-local")
        val payload = "cached pack".toByteArray(Charsets.UTF_8)
        val pack = packOf(payload)
        PackInstaller(dir, FakeFetch(payload)).install(pack)

        val secondFetch = FakeFetch(payload, failWith = IOException("本地命中不该碰网"))
        val report = PackInstaller(dir, secondFetch).install(pack)

        assertFalse("本地命中不该说下载过", report.downloaded)
        assertEquals(payload.size.toLong(), report.bytes)
        assertEquals("本地命中必须一次网络都不碰", 0, secondFetch.calls)
    }

    @Test
    fun badLocalCopyIsReplacedByAFreshDownload() {
        val dir = tempDir("pack-heal")
        val payload = "right one".toByteArray(Charsets.UTF_8)
        File(dir, "kotlin-1.0.0.pack").writeText("corrupted")

        val fetch = FakeFetch(payload)
        val report = PackInstaller(dir, fetch).install(packOf(payload))

        assertTrue("坏本地件要自愈重下", report.downloaded)
        assertEquals(1, fetch.calls)
        assertTrue(payload.contentEquals(report.file.readBytes()))
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun stalePartFileIsClearedBeforeInstall() {
        val dir = tempDir("pack-stale")
        val payload = "fresh".toByteArray(Charsets.UTF_8)
        File(dir, "kotlin-1.0.0.pack.part").writeText("stale half")

        val report = PackInstaller(dir, FakeFetch(payload)).install(packOf(payload))

        assertEquals("旧半截要被清掉，只留成品", 1, dir.listFiles()!!.size)
        assertEquals("kotlin-1.0.0.pack", report.file.name)
    }

    @Test
    fun progressReportsMonotonicCumulativeBytes() {
        val dir = tempDir("pack-progress")
        val payload = "progress data".toByteArray(Charsets.UTF_8)
        val seen = ArrayList<Long>()

        PackInstaller(dir, FakeFetch(payload, chunk = 4)).install(packOf(payload)) { done, _ -> seen += done }

        assertEquals("最后一块的累计必须等于总字节", payload.size.toLong(), seen.last())
        assertTrue("进度只增不减：$seen", seen == seen.sorted())
    }

    @Test
    fun unusableTargetDirectoryIsRejected() {
        val base = tempDir("pack-dir")
        val blocked = File(base, "blocker")
        blocked.writeText("occupied")

        val error = expectReject {
            PackInstaller(blocked, FakeFetch("x".toByteArray(Charsets.UTF_8))).install(packOf("x".toByteArray(Charsets.UTF_8)))
        }

        assertTrue("目录建不出来要指名道姓：$error", error.contains("装机目录"))
    }

    @Test
    fun limitsMustBePositive() {
        try {
            PackLimits(maxBytes = 0)
        } catch (e: IllegalArgumentException) {
            assertTrue("拒绝要带原因", (e.message ?: "").isNotEmpty())
            return
        }
        throw AssertionError("0 上限本该拒绝")
    }

    /** 拒收路径统一入口：必须抛 PackInstallReject 且消息非空。 */
    private fun expectReject(block: () -> Any?): String {
        try {
            block()
        } catch (e: PackInstallReject) {
            val message = e.message ?: ""
            assertTrue("拒绝必须给原因", message.isNotEmpty())
            return message
        }
        throw AssertionError("本该拒绝但放行了")
    }
}
