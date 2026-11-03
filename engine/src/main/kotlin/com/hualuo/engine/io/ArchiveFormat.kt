package com.hualuo.engine.io

import java.util.Locale

/**
 * 格式识别：**只认文件头魔数，绝不按扩展名分派动作**。
 *
 * 为什么立这条死规矩：旧 Agora 是拿扩展名决定用哪个解压器和 MIME 的，
 * 于是服务端只回 `application/octet-stream`、文件名没有后缀、或者后缀撒谎时，
 * 它就直接报错；更糟的是"名字像 zip 其实不是"会喂给错误的解析器，崩在看不见的地方。
 * 这里扩展名只用来**提示和对照**，真正决定动作的永远是头几个字节。
 *
 * 复合包（tar.gz 之类）不在这里想办法：先按头部结果处理最外层，
 * 脱掉一层之后**拿剩下的字节再调一次本文件**，不要试图一次看穿。
 */
enum class ArchiveFormat(val displayName: String, val actionable: Boolean, val nextStep: String) {
    ZIP("zip 压缩包", true, "用 zip 逐条目解；每条都要过 ExtractGuard，先验名字再落盘"),
    GZIP("gzip 单流", true, "先脱掉 gz 这一层；剩下的字节再探一次头（多数是 tar）"),
    TAR("tar 打包（本身不压缩）", true, "逐条目过 ExtractGuard 后直接落盘"),
    BZIP2("bzip2 单流", false, "本工具只识别不解压：内置没有 bzip2 实现，别硬上，把话说清楚让用户换 zip 或 tar.gz"),
    XZ("xz 单流", false, "本工具只识别不解压：内置没有 xz 实现，需要外部依赖再议"),
    ZSTD("zstd 单流", false, "本工具只识别不解压：内置没有 zstd 实现"),
    SEVEN_Z("7z 压缩包", false, "本工具只识别不解压：java 没有内置 7z，需要外部库"),
    RAR4("rar（老版头）", false, "闭格式，只识别不解压"),
    RAR5("rar（新版头）", false, "闭格式，只识别不解压"),
    UNKNOWN("认不出来", false, "不要按扩展名猜着解；把头几个字节的十六进制报出来，再决定加不加新识别项"),
}

/**
 * 探测结论。
 *
 * [nameSays] 与 [disagreesWithExtension] 存在的唯一目的：把"扩展名在撒谎"这件事
 * 变成一条能显示给用户、也能被测试断言的事实，而不是让它静默地走进错误的解析器。
 * [matchedBytes] 只算**真的比对过的字节数**，不许多报——上一版这里认了两个字节却写 4，
 * 属于"数字比结论精确"，宁可少说。
 */
data class FormatProbe(
    val format: ArchiveFormat,
    val matchedBytes: Int,
    val nameSays: ArchiveFormat?,
    val disagreesWithExtension: Boolean,
    val conclusion: String,
)

/** 只用于提示与对照。任何"按它选解压器"的调用都是走偏路。 */
fun formatFromExtension(fileName: String): ArchiveFormat? {
    val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
    return when (ext) {
        // apk/aar/jar/ear 本质都是 zip，旧 Agora 就是在这儿把它们当未知类型处理失败的
        "zip", "apk", "aar", "jar", "ear", "cbz", "epub" -> ArchiveFormat.ZIP
        "gz", "tgz" -> ArchiveFormat.GZIP
        "tar" -> ArchiveFormat.TAR
        "bz2", "tbz2", "tbz" -> ArchiveFormat.BZIP2
        "xz", "txz" -> ArchiveFormat.XZ
        "zst" -> ArchiveFormat.ZSTD
        "7z" -> ArchiveFormat.SEVEN_Z
        "rar" -> ArchiveFormat.RAR4
        else -> null
    }
}

/**
 * 看头识格式。[head] 只要前面一小段（**建议给 512 字节，tar 的魔数在偏移 257 处**），
 * 绝不允许为了识别格式把整个文件读进内存——那是旧 Agora 闪退的根因之一。
 */
fun probeArchiveFormat(head: ByteArray, offset: Int = 0): FormatProbe {
    fun has(count: Int): Boolean = head.size >= offset + count

    val format: ArchiveFormat
    val matched: Int

    when {
        has(2) && startsWithAt(head, offset, 0x50, 0x4B) -> {
            format = ArchiveFormat.ZIP // 后面两字节分 0304／0506／0708，动作都一样，不假装认了 4 字节
            matched = 2
        }
        has(2) && startsWithAt(head, offset, 0x1F, 0x8B) -> {
            format = ArchiveFormat.GZIP
            matched = 2
        }
        has(3) && startsWithAt(head, offset, 0x42, 0x5A, 0x68) -> {
            format = ArchiveFormat.BZIP2
            matched = 3
        }
        has(6) && startsWithAt(head, offset, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> {
            format = ArchiveFormat.XZ
            matched = 6
        }
        has(4) && startsWithAt(head, offset, 0x28, 0xB5, 0x2F, 0xFD) -> {
            format = ArchiveFormat.ZSTD
            matched = 4
        }
        has(6) && startsWithAt(head, offset, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> {
            format = ArchiveFormat.SEVEN_Z
            matched = 6
        }
        has(8) && startsWithAt(head, offset, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00) -> {
            format = ArchiveFormat.RAR5
            matched = 8
        }
        has(7) && startsWithAt(head, offset, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00) -> {
            format = ArchiveFormat.RAR4
            matched = 7
        }
        has(262) && startsWithAt(head, offset + 257, 0x75, 0x73, 0x74, 0x61, 0x72) -> {
            format = ArchiveFormat.TAR
            matched = 5
        }
        else -> {
            format = ArchiveFormat.UNKNOWN
            matched = 0
        }
    }

    return FormatProbe(
        format = format,
        matchedBytes = matched,
        nameSays = null,
        disagreesWithExtension = false,
        conclusion = if (format == ArchiveFormat.UNKNOWN) {
            "头 ${hexPrefix(head, offset, 8)} 认不出来。动作：${ArchiveFormat.UNKNOWN.nextStep}"
        } else {
            "头匹配 ${format.displayName}（认了 $matched 字节）。动作：${format.nextStep}"
        },
    )
}

/** 带文件名一起探：多出来的价值只有"能报告扩展名与文件头不一致"这一件事。 */
fun probeArchiveFormatNamed(fileName: String?, head: ByteArray, offset: Int = 0): FormatProbe {
    val byHead = probeArchiveFormat(head, offset)
    val byName = fileName?.let { formatFromExtension(it) }
    val disagree = byName != null && byName != byHead.format
    val note = when {
        fileName == null -> "没有文件名可对照。"
        byName == null -> "扩展名说明不了格式（${fileName.substringAfterLast('.', "无后缀")}），全靠文件头。"
        disagree -> "注意：扩展名说 $byName，文件头说 ${byHead.format}，**以文件头为准**。"
        else -> "扩展名与文件头一致。"
    }
    return byHead.copy(
        nameSays = byName,
        disagreesWithExtension = disagree,
        conclusion = "${byHead.conclusion}；$note",
    )
}

private fun startsWithAt(head: ByteArray, from: Int, vararg magic: Int): Boolean {
    if (from < 0 || head.size < from + magic.size) return false
    for (i in magic.indices) {
        if (head[from + i].toInt() and 0xFF != magic[i]) return false
    }
    return true
}

private fun hexPrefix(head: ByteArray, from: Int, count: Int): String {
    if (head.size <= from) return "（空）"
    return (from until minOf(head.size, from + count))
        .joinToString(" ") { String.format(Locale.US, "%02X", head[it].toInt() and 0xFF) }
}
