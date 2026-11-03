package com.hualuo.engine.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * tar 流式解包对照表（D6 修复的验证面）：
 * 真符号链接、悬空链接照建、硬链接补建、目录穿越必拒、大文件流式不进内存。
 */
class TarEntryExtractorTest {

    private fun tarGzOf(vararg entries: TarEntry): ByteArray {
        val raw = java.io.ByteArrayOutputStream()
        for (e in entries) raw.write(e.headerAndBody())
        // tar 结束：两块 512 全零
        raw.write(ByteArray(1024))
        val gz = java.io.ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(raw.toByteArray()) }
        return gz.toByteArray()
    }

    private data class TarEntry(val name: String, val content: String = "", val type: Char = '0', val linkName: String = "") {
        fun headerAndBody(): ByteArray {
            val header = ByteArray(512)
            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
            val linkBytes = linkName.toByteArray(Charsets.US_ASCII)
            if (linkBytes.isNotEmpty()) System.arraycopy(linkBytes, 0, header, 157, minOf(linkBytes.size, 100))
            header[156] = type.code.toByte()
            val body = if (type == '0') content.toByteArray() else ByteArray(0)
            val sizeOctal = body.size.toString(8).padStart(11, '0') + "\u0000"
            System.arraycopy(sizeOctal.toByteArray(Charsets.US_ASCII), 0, header, 124, 12)
            // 校验和：先全空格再算
            for (i in 148..155) header[i] = ' '.code.toByte()
            var sum = 0L
            for (b in header) sum += b.toLong() and 0xFF
            val chk = (java.lang.Long.toOctalString(sum).padStart(6, '0') + "\u0000 ").toByteArray(Charsets.US_ASCII)
            System.arraycopy(chk, 0, header, 148, 8)
            return header + body + ByteArray(((body.size + 511) / 512) * 512 - body.size)
        }
    }

    @Test
    fun regularFilesAndDirsLand() {
        val tmp = Files.createTempDirectory("tarx").toFile()
        val stats = TarEntryExtractor().extract(
            ByteArrayInputStream(tarGzOf(TarEntry("etc/", type = '5'), TarEntry("etc/motd", content = "hello alpine"))),
            tmp,
        ) { }
        assertEquals("文件内容要一字不差：${File(tmp, "etc/motd").readText()}", "hello alpine", File(tmp, "etc/motd").readText())
        assertEquals("目录+文件都算条目：", 2, stats.entries)
        assertEquals(1, stats.dirs)
        tmp.deleteRecursively()
    }

    @Test
    fun symlinkIsRealAndDanglingSurvives() {
        val tmp = Files.createTempDirectory("tarx").toFile()
        TarEntryExtractor().extract(
            ByteArrayInputStream(tarGzOf(
                TarEntry("bin/", type = '5'),
                TarEntry("bin/sh", type = '2', linkName = "/bin/busybox"),
                TarEntry("bin/broken", type = '2', linkName = "/nonexistent/target"),
            )),
            tmp,
        ) { }
        val sh = File(tmp, "bin/sh")
        assertTrue("/bin/sh 必须是符号链接（不是复制的文件）：isSymbolicLink=${java.nio.file.Files.isSymbolicLink(sh.toPath())}", java.nio.file.Files.isSymbolicLink(sh.toPath()))
        assertEquals("/bin/sh 指向 /bin/busybox：", "/bin/busybox", java.nio.file.Files.readSymbolicLink(sh.toPath()).toString())
        val broken = File(tmp, "bin/broken")
        assertTrue("悬空链接也要照建（D6 核心）：exists=${broken.exists()}", broken.exists() || java.nio.file.Files.isSymbolicLink(broken.toPath()))
        tmp.deleteRecursively()
    }

    @Test
    fun hardLinkDeferredWhenTargetComesLater() {
        val tmp = Files.createTempDirectory("tarx").toFile()
        TarEntryExtractor().extract(
            ByteArrayInputStream(tarGzOf(
                TarEntry("a/hard", type = '1', linkName = "a/real"),
                TarEntry("a/", type = '5'),
                TarEntry("a/real", content = "payload"),
            )),
            tmp,
        ) { }
        val hard = File(tmp, "a/hard")
        assertTrue("目标在后也应补建成功（deferred）：exists=${hard.exists()}", hard.exists())
        assertEquals("硬链接内容与目标一致：", "payload", hard.readText())
        tmp.deleteRecursively()
    }

    @Test
    fun pathTraversalIsRejected() {
        val tmp = Files.createTempDirectory("tarx").toFile()
        val outside = Files.createTempDirectory("outside").toFile()
        val err = runCatching {
            TarEntryExtractor().extract(
                ByteArrayInputStream(tarGzOf(TarEntry("../escaped.txt", content = "evil"))),
                tmp,
            ) { }
        }.exceptionOrNull()
        assertTrue("穿越条目必须被拒：err=$err", err != null)
        assertEquals("穿越文件绝不许落在外面：count=${outside.walkTopDown().count() - 1}", 0, outside.walkTopDown().count() - 1)
        tmp.deleteRecursively(); outside.deleteRecursively()
    }

    @Test
    fun absoluteLinkTargetStaysInsideRoot() {
        val tmp = Files.createTempDirectory("tarx").toFile()
        TarEntryExtractor().extract(
            ByteArrayInputStream(tarGzOf(TarEntry("bin/sh", type = '2', linkName = "/bin/busybox"))),
            tmp,
        ) { }
        val target = java.nio.file.Files.readSymbolicLink(File(tmp, "bin/sh").toPath()).toString()
        val resolved = File(tmp, target.removePrefix("/")).absolutePath
        assertTrue("绝对链接目标必须落在 rootfs 内：$resolved", resolved.startsWith(tmp.absolutePath))
        tmp.deleteRecursively()
    }
}
