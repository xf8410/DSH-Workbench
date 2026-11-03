package com.hualuo.engine.sandbox

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolutePathString

/**
 * rootfs tar.gz 流式解包（修复 D6 + 满足红线二）。
 *
 * 旧实现的坑（docs/SANDBOX-LOGIC 修复方案第 6 条）：解包复制符号链接时复制的是
 * 链接指向的内容或干脆丢弃，`/bin/sh` 这类关键链接一旦丢，apk upgrade 重解 /bin/sh
 * 就死锁。从这里开始：
 *  - 符号链接用 [Files.createSymbolicLink] 真建（悬空链接照建不丢，Android 私有目录支持）；
 *  - 硬链接用 [Files.createLink]，目标未落盘时记账，收尾补建一轮；
 *  - 逐条目 64KB 缓冲流式写入，任何文件都不整读进内存；
 *  - 目录穿越防护：条目解析后的规范路径必须仍在 [targetDir] 内（与 ExtractGuard 同纪律）。
 */
class TarEntryExtractor(private val limits: Limits = Limits()) {

    data class Limits(
        val maxEntries: Int = 120_000,
        val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
        val maxSingleBytes: Long = 512L * 1024 * 1024,
    )

    data class Stats(val entries: Int, val fileBytes: Long, val symlinks: Int, val hardlinks: Int, val dirs: Int)

    private class DeferredHardLink(val linkPath: Path, val targetName: String)

    fun extract(gzipTar: InputStream, targetDir: File, onProgress: (String) -> Unit): Stats {
        val root = targetDir.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS)
        var entries = 0
        var fileBytes = 0L
        var symlinks = 0
        var dirs = 0
        val deferred = ArrayList<DeferredHardLink>()
        val buffer = ByteArray(64 * 1024)

        java.util.zip.GZIPInputStream(gzipTar, 64 * 1024).use { raw ->
            var longName: String? = null
            var paxPath: String? = null
            while (true) {
                val header = ByteArray(512)
                if (!readFully(raw, header)) break
                if (header.all { it.toInt() == 0 }) {
                    // 一条全零块：tar 记录结束或块对齐填充，继续读下一段，连续两条全零即结束。
                    if (longName != null || paxPath != null) continue
                    break
                }
                val nameBytes = paxPath ?: longName
                paxPath = null
                val baseName = nameBytes ?: readStr(header, 0, 100) ?: continue
                longName = null
                val size = readOctal(header, 124, 12)
                val typeflag = header[156].toInt().toChar()
                val linkName = readStr(header, 157, 100)

                when (typeflag) {
                    'L' -> { // GNU long name：紧跟 size 字节就是真名
                        if (size > limits.maxSingleBytes) {
                            throw SandboxError(SandboxError.Kind.IO, stderrSnippet = "tar 条目名 $size 字节超上限").toException()
                        }
                        val buf = ByteArray(size.toInt())
                        readFully(raw, buf)
                        longName = String(buf, Charsets.UTF_8).trimEnd('\u0000')
                        continue
                    }
                    'K' -> { // GNU long link name：忽略（链接目标超长的场景 rootfs 里没有）
                        skipFully(raw, size)
                        continue
                    }
                    'x', 'g' -> { // PAX 扩展头：只关心 path= 覆盖
                        if (size > limits.maxSingleBytes) {
                            throw SandboxError(SandboxError.Kind.IO, stderrSnippet = "PAX 头 $size 字节超上限").toException()
                        }
                        val buf = ByteArray(size.toInt())
                        if (!readFully(raw, buf)) break
                        val text = String(buf, Charsets.UTF_8)
                        val m = Regex("path=([^\\n]+)").find(text)
                        if (typeflag == 'x' && m != null) paxPath = m.groupValues[1]
                        continue
                    }
                }

                entries++
                if (entries > limits.maxEntries) {
                    throw SandboxError(SandboxError.Kind.IO, stderrSnippet = "解包条目超过上限 ${limits.maxEntries}").toException()
                }
                if (size > limits.maxSingleBytes) {
                    throw SandboxError(SandboxError.Kind.IO, stderrSnippet = "单文件超限：$baseName $size 字节").toException()
                }
                if (fileBytes + size > limits.maxTotalBytes) {
                    throw SandboxError(SandboxError.Kind.IO, stderrSnippet = "解包总量超过上限 ${limits.maxTotalBytes} 字节").toException()
                }

                when (typeflag) {
                    '5' -> { // 目录
                        safeResolve(root, baseName).toFile().mkdirs()
                        dirs++
                    }
                    '2' -> { // 符号链接：真建，悬空照建
                        val target = linkName ?: ""
                        val p = safeResolve(root, baseName)
                        Files.createDirectories(p.parent)
                        if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) Files.delete(p)
                        runCatching { Files.createSymbolicLink(p, java.nio.file.Paths.get(target)) }
                            .onFailure {
                                // 个别文件系统不支持符号链接：退化为记诊断，不装死
                                onProgress("符号链接建失败（目标=$target）：${it.message}")
                            }
                        symlinks++
                    }
                    '1' -> { // 硬链接
                        val from = safeResolve(root, baseName)
                        val targetText = linkName
                        val to = targetText?.let { runCatching { safeResolve(root, it) }.getOrNull() }
                        if (to != null && targetText != null) {
                            if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                                Files.createDirectories(from.parent)
                                if (Files.exists(from, LinkOption.NOFOLLOW_LINKS)) Files.delete(from)
                                runCatching { Files.createLink(from, to) }
                                    .onFailure { deferred.add(DeferredHardLink(from, targetText)) }
                            } else {
                                deferred.add(DeferredHardLink(from, targetText))
                            }
                        }
                    }
                    '0', '7', '\u0000' -> { // 普通文件（'7' 连续文件同 '0' 处理）
                        val p = safeResolve(root, baseName)
                        Files.createDirectories(p.parent)
                        if (Files.exists(p, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                            Files.delete(p)
                        }
                        java.io.FileOutputStream(p.toFile(), false).use { out ->
                            var left = size
                            while (left > 0) {
                                val chunk = raw.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                                if (chunk < 0) throw SandboxError(SandboxError.Kind.IO, stderrSnippet="tar 流提前结束：$baseName").toException()
                                out.write(buffer, 0, chunk)
                                left -= chunk
                            }
                        }
                        fileBytes += size
                        if (entries % 2000 == 0) onProgress("已解包 $entries 个条目")
                    }
                    else -> skipFully(raw, size) // 3/4/6 块设备字符设备 FIFO 等：rootfs 里跳过
                }
                // 512 对齐填充
                val pad = (512 - (size % 512)) % 512
                if (pad > 0) skipFully(raw, pad)
            }
        }

        // 收尾补建硬链接（目标那时已落盘）
        for (d in deferred) {
            val to = root.resolve(d.targetName).normalize()
            if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                runCatching { Files.createLink(d.linkPath, to) }
            }
        }
        onProgress("解包完成：$entries 条目 / $symlinks 链接 / $dirs 目录")
        return Stats(entries, fileBytes, symlinks, deferred.size, dirs)
    }

    /** 条目名解析到 root 内；解析后不在 root 内（../ 穿越、绝对路径逃逸）一律拒绝。 */
    private fun safeResolve(root: Path, entryName: String): Path {
        val cleaned = entryName.trimStart('/')
        val p = root.resolve(cleaned).normalize()
        if (!p.startsWith(root)) {
            throw SandboxError(SandboxError.Kind.REJECTED, stderrSnippet = "tar 条目越界：$entryName").toException()
        }
        return p
    }

    private fun readStr(header: ByteArray, off: Int, len: Int): String? {
        var end = off
        val stop = off + len
        while (end < stop && header[end] != 0.toByte()) end++
        if (end == off) return null
        return String(header, off, end - off, Charsets.UTF_8).trimEnd('\u0000')
    }

    companion object {
        /** 静态读八进制字段（APKINDEX.tar.gz 在宿主侧解索引时复用同一套 tar 头解析）。 */
        internal fun readOctalStatic(header: ByteArray, off: Int, len: Int): Long {
            var s = ""
            for (i in off until off + len) {
                val c = header[i].toInt().toChar()
                if (c == '\u0000' || c == ' ') {
                    if (s.isNotEmpty()) break else continue
                }
                s += c
            }
            return if (s.isEmpty()) 0L else s.toLongOrNull(8) ?: 0L
        }

        /** 静态跳段（同上复用）。 */
        internal fun skipFullyStatic(input: InputStream, count: Long) {
            var left = count
            val buf = ByteArray(64 * 1024)
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) return
                left -= n
            }
        }
    }

    private fun readOctal(header: ByteArray, off: Int, len: Int): Long {
        var s = ""
        for (i in off until off + len) {
            val c = header[i].toInt().toChar()
            if (c == '\u0000' || c == ' ') {
                if (s.isNotEmpty()) break else continue
            }
            s += c
        }
        return if (s.isEmpty()) 0L else s.toLongOrNull(8) ?: 0L
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return off == buf.size && buf.size == 0
            off += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) return
            left -= n
        }
    }
}
