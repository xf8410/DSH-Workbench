package com.hualuo.engine.lsp

import com.hualuo.engine.io.ExtractLimits
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解包器契约（纯 JVM）：成功路径、入口三验（不在/非文件/0 字节）、ExtractGuard 真消费
 * （遍历条目与两道上限各钉一条）、空包、坏 zip、缺包件、旧目录替换、脏暂存清理。
 *
 * 所有 zip 都在测试里现造（ZipOutputStream 写临时文件），不碰网络、不依赖外部包。
 */
class PackExtractorTest {

    private fun packOf(entryName: String): LanguagePack = LanguagePack(
        id = "kotlin",
        displayName = "Kotlin 语言服务",
        version = "1.0.0",
        serverBinary = entryName,
        sha256Hex = "a".repeat(64),
        downloadUrl = "https://example.com/p.pack",
    )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun tempDir(name: String): File = Files.createTempDirectory(name).toFile()

    private fun packFileIn(dir: File, bytes: ByteArray): File {
        val file = File(dir, "kotlin-1.0.0.pack")
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun happyPathExtractsEntryAndRenamesAtomically() {
        val base = tempDir("extract-happy")
        val payload = "echo server".toByteArray(Charsets.UTF_8)
        val zip = zipOf("bin/server.sh" to payload, "readme.txt" to "hi".toByteArray(Charsets.UTF_8))

        val report = PackExtractor().extract(packOf("bin/server.sh"), packFileIn(base, zip), File(base, "kotlin-1.0.0"))

        assertEquals("bin/server.sh", report.entryPath)
        assertEquals(payload.size.toLong(), report.entryBytes)
        assertTrue(report.targetDir.isDirectory)
        assertTrue(payload.contentEquals(File(report.targetDir, "bin/server.sh").readBytes()))
        assertFalse("成功后不许留暂存目录", File(base, "kotlin-1.0.0.unpack").exists())
    }

    @Test
    fun missingEntryFileIsRejectedAndStagingCleaned() {
        val base = tempDir("extract-noentry")
        val zip = zipOf("readme.txt" to "hi".toByteArray(Charsets.UTF_8))

        val error = expectReject {
            PackExtractor().extract(packOf("bin/server.sh"), packFileIn(base, zip), File(base, "out"))
        }

        assertTrue("必须点名入口不在：$error", error.contains("服务入口文件"))
        assertFalse(File(base, "out").exists())
        assertFalse(File(base, "out.unpack").exists())
    }

    @Test
    fun zeroByteEntryIsRejected() {
        val base = tempDir("extract-zero")
        val zip = zipOf("bin/server.sh" to ByteArray(0))

        val error = expectReject {
            PackExtractor().extract(packOf("bin/server.sh"), packFileIn(base, zip), File(base, "out"))
        }

        assertTrue("0 字节入口要明说：$error", error.contains("0 字节"))
        assertFalse(File(base, "out").exists())
    }

    @Test
    fun traversalEntryNameIsStoppedByGuard() {
        val base = tempDir("extract-traversal")
        val zip = zipOf(
            "../evil.txt" to "boom".toByteArray(Charsets.UTF_8),
            "bin/server.sh" to "ok".toByteArray(Charsets.UTF_8),
        )

        val error = expectReject {
            PackExtractor().extract(packOf("bin/server.sh"), packFileIn(base, zip), File(base, "out"))
        }

        assertTrue("安全闸要出面：$error", error.contains("安全闸"))
        assertFalse("越界文件一个都不许在目标外出现", File(base, "evil.txt").exists())
        assertFalse(File(base, "out").exists())
        assertFalse(File(base, "out.unpack").exists())
    }

    @Test
    fun entryCountLimitIsEnforced() {
        val base = tempDir("extract-count")
        val zip = zipOf(
            "a.txt" to "1".toByteArray(Charsets.UTF_8),
            "b.txt" to "2".toByteArray(Charsets.UTF_8),
            "c.txt" to "3".toByteArray(Charsets.UTF_8),
        )

        val error = expectReject {
            PackExtractor(ExtractLimits(maxEntries = 2)).extract(packOf("a.txt"), packFileIn(base, zip), File(base, "out"))
        }

        assertTrue("条目数上限要被点名：$error", error.contains("条目数已达上限"))
        assertFalse(File(base, "out").exists())
    }

    @Test
    fun entrySizeLimitIsEnforced() {
        val base = tempDir("extract-size")
        val zip = zipOf("big.bin" to ByteArray(200) { 7 })

        val error = expectReject {
            PackExtractor(ExtractLimits(maxEntryBytes = 100, maxTotalBytes = 100))
                .extract(packOf("big.bin"), packFileIn(base, zip), File(base, "out"))
        }

        assertTrue("单条目上限要被点名：$error", error.contains("单条目"))
        assertFalse(File(base, "out").exists())
    }

    @Test
    fun emptyZipIsRejected() {
        val base = tempDir("extract-empty")
        val zip = zipOf()

        val error = expectReject {
            PackExtractor().extract(packOf("a.txt"), packFileIn(base, zip), File(base, "out"))
        }

        assertTrue("空包要明说：$error", error.contains("一个文件都没有"))
    }

    @Test
    fun nonZipBytesAreRejected() {
        val base = tempDir("extract-badzip")

        val error = expectReject {
            PackExtractor().extract(packOf("a.txt"), packFileIn(base, "definitely not a zip".toByteArray()), File(base, "out"))
        }

        assertTrue("坏结构要明说：$error", error.contains("不是有效的 zip"))
        assertFalse(File(base, "out").exists())
    }

    @Test
    fun missingPackFileIsRejectedWithDirection() {
        val base = tempDir("extract-missing")

        val error = expectReject {
            PackExtractor().extract(packOf("a.txt"), File(base, "never-downloaded.pack"), File(base, "out"))
        }

        assertTrue("缺包要指出路：$error", error.contains("先装它再解"))
    }

    @Test
    fun secondExtractReplacesOldTargetDir() {
        val base = tempDir("extract-replace")
        val v1 = zipOf("bin/server.sh" to "v1".toByteArray(Charsets.UTF_8))
        val target = File(base, "out")
        PackExtractor().extract(packOf("bin/server.sh"), packFileIn(base, v1), target)
        File(target, "stale.txt").writeText("old junk")

        val v2 = zipOf("bin/server.sh" to "v2".toByteArray(Charsets.UTF_8))
        val second = File(base, "kotlin-1.0.1.pack")
        second.writeBytes(v2)
        val report = PackExtractor().extract(packOf("bin/server.sh"), second, target)

        assertEquals("v2", File(report.targetDir, "bin/server.sh").readText())
        assertFalse("旧目录的杂物必须随旧目录一起走", File(target, "stale.txt").exists())
        assertFalse(File(base, "out.unpack").exists())
    }

    @Test
    fun staleStagingFromPreviousRunIsCleared() {
        val base = tempDir("extract-stale")
        val staging = File(base, "out.unpack")
        staging.mkdirs()
        File(staging, "junk.txt").writeText("leftover")

        val zip = zipOf("bin/server.sh" to "ok".toByteArray(Charsets.UTF_8))
        val report = PackExtractor().extract(packOf("bin/server.sh"), packFileIn(base, zip), File(base, "out"))

        assertFalse("旧暂存必须被清掉重建", File(report.targetDir, "junk.txt").exists())
        assertFalse(File(base, "out.unpack").exists())
    }

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
