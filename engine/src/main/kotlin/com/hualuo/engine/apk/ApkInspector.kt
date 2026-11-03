package com.hualuo.engine.apk

import com.hualuo.engine.io.ArchiveFormat
import com.hualuo.engine.io.DEFAULT_BUFFER_BYTES
import com.hualuo.engine.io.probeArchiveFormatNamed
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * APK 检查件（560 清单 121-160 域的可离线子集，引擎件纯 JVM）：
 *  - 魔数验证（136，复用 io.probeArchiveFormat，结论原文照报）；
 *  - ZIP 条目清单：名字/压缩方式/压缩与解压大小/CRC（143-146，java.util.zip 读中央目录）；
 *  - ABI Split 识别（150）：lib/<abi>/ 聚合；
 *  - 重复文件识别（149）：CRC 相同且非空分桶；
 *  - SHA-256（148）：流式（红线二：整文件一口气进内存的写法不存在）。
 *
 * AndroidManifest 二进制解析（151-153）不在本件——AXML 解析独立成刀。
 * 零脱敏：条目名原文进出。
 */
object ApkInspector {

    /** 一条 ZIP 条目账。 */
    data class EntryInfo(
        val name: String,
        val method: String,
        val compressedBytes: Long,
        val sizeBytes: Long,
        val crcHex: String,
    )

    /** 整份检查报告。 */
    data class ApkReport(
        val fileName: String,
        val sizeBytes: Long,
        val magicOk: Boolean,
        val magicNote: String,
        val entries: List<EntryInfo>,
        val totalUncompressedBytes: Long,
        val abis: List<String>,
        val duplicateGroups: List<List<String>>,
        val sha256Hex: String,
    )

    fun inspect(file: File): ApkReport {
        val head = file.inputStream().use { input ->
            val buf = ByteArray(8)
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
            buf.copyOf(off)
        }
        val probe = probeArchiveFormatNamed(file.name, head)
        // 魔数不对也继续出账（用户可能就想看这个文件到底装了什么）——报告里如实标
        val magicOk = probe.format == ArchiveFormat.ZIP

        val entries = mutableListOf<EntryInfo>()
        var totalUncompressed = 0L
        val crcToNames = LinkedHashMap<String, MutableList<String>>()
        val abiSet = LinkedHashSet<String>()
        if (magicOk) {
            // 非 ZIP 的输入在这段会炸（ZipException）：魔数不对就跳过条目解析，账面留空但不炸
            val zf = ZipFile(file)
            try {
                val en = zf.entries()
                while (en.hasMoreElements()) {
                    val e: ZipEntry = en.nextElement()
                    val method = when (e.method) {
                        ZipEntry.STORED -> "存储"
                        ZipEntry.DEFLATED -> "Deflate"
                        else -> "未知(${e.method})"
                    }
                    entries += EntryInfo(e.name, method, e.compressedSize, e.size, "%08x".format(e.crc))
                    totalUncompressed += e.size
                    if (e.size > 0) {
                        crcToNames.getOrPut("%08x".format(e.crc)) { mutableListOf() }.add(e.name)
                    }
                    // lib/<abi>/... -> ABI 聚合
                    if (e.name.startsWith("lib/") && e.name.count { it == '/' } >= 2) {
                        abiSet.add(e.name.removePrefix("lib/").substringBefore('/'))
                    }
                }
            } finally {
                zf.close()
            }
        }

        return ApkReport(
            fileName = file.name,
            sizeBytes = file.length(),
            magicOk = magicOk,
            magicNote = probe.conclusion,
            entries = entries,
            totalUncompressedBytes = totalUncompressed,
            abis = abiSet.toList(),
            duplicateGroups = crcToNames.values.filter { it.size > 1 },
            sha256Hex = sha256Hex(file),
        )
    }

    /** 流式算 SHA-256（不整读进内存）。 */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_BYTES)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** 报告渲染成给用户看的多行文本（纯 JVM 可测）。 */
fun ApkInspector.ApkReport.render(): String {
    val r = this
    return buildString {
        appendLine("文件：${r.fileName}（${r.sizeBytes / 1024} KB）")
        appendLine("魔数：${if (r.magicOk) "ZIP 头成立" else "不成立——${r.magicNote}"}")
        appendLine("条目：${r.entries.size} 个，解压合计 ${r.totalUncompressedBytes / 1024} KB")
        if (r.abis.isNotEmpty()) appendLine("ABI：${r.abis.joinToString("、")}")
        r.duplicateGroups.forEach { g ->
            val crc = r.entries.firstOrNull { it.name == g[0] }?.crcHex.orEmpty()
            appendLine("重复文件（CRC $crc）：${g.joinToString("、")}")
        }
        appendLine("SHA-256：${r.sha256Hex}")
    }
}
