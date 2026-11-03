package com.hualuo.engine.language

import kotlin.math.abs

/**
 * 进制查看件（查看器第三件）：hex dump 行账 + 任意进制换算 + 浮点分解。
 * 纯 JVM、零依赖；所有输入都有兜底账，没有"这个数看不了"。
 */
object HexDump {

    /** 一行 dump：偏移 + 定宽十六进制（分两半格）+ ASCII 侧栏（不可见字符给点号）。 */
    data class DumpRow(val offset: Long, val hex: String, val ascii: String)

    const val WIDTH = 16

    /** 把一段字节排成 dump 行（分块查看时 [baseOffset] 接着上一块的账走）。 */
    fun dump(bytes: ByteArray, baseOffset: Long = 0L, width: Int = WIDTH): List<DumpRow> {
        val rows = ArrayList<DumpRow>((bytes.size + width - 1) / width)
        var i = 0
        while (i < bytes.size) {
            val end = minOf(i + width, bytes.size)
            val hexSb = StringBuilder()
            val asciiSb = StringBuilder()
            for (j in i until i + width) {
                if (j < end) {
                    val b = bytes[j].toInt() and 0xFF
                    hexSb.append(HEX[b shr 4]).append(HEX[b and 0x0F]).append(if (j == i + width / 2 - 1) "  " else " ")
                    asciiSb.append(if (b in 0x20..0x7E) b.toChar() else '·')
                } else {
                    // 补位对齐
                    hexSb.append("   ")
                }
            }
            rows += DumpRow(baseOffset + i, hexSb.toString().trimEnd(), asciiSb.toString())
            i = end
        }
        return rows
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * 文本/二进制嗅探：有 \0 或控制字符比例超标 = 二进制。
     * 查看器用它决定走文本页还是 hex 页——两边都能看，这判的只是打开方式。
     */
    fun looksBinary(bytes: ByteArray): Boolean {
        val sample = bytes.take(8192)
        if (sample.isEmpty()) return false
        var control = 0
        for (b in sample) {
            val v = b.toInt() and 0xFF
            if (v == 0) return true
            if (v < 0x09 || (v in 0x0E..0x1F)) control++
        }
        return control * 100 > sample.size * 5
    }

    /** UTF-8 尝试解码：解得动（没有替换符就当全成）算文本。 */
    fun decodableAsText(bytes: ByteArray): Boolean {
        return runCatching {
            val s = String(bytes, Charsets.UTF_8)
            !s.contains('\uFFFD')
        }.getOrDefault(false)
    }

    /** 一次进制换算的账：同一数值的四副面孔。 */
    data class RadixView(
        val value: Long,
        val bin: String,
        val oct: String,
        val dec: String,
        val hex: String,
        val bits: String,
    )

    /**
     * 解析输入（自动识别 0x/0b/0o 前缀，十进制兜底；也接受指定进制），
     * 返回四进制视图 + 按字节分组的三二位账。超出 Long 范围给 null（调用方报人话）。
     */
    fun radix(input: String, fromRadix: Int = 0): RadixView? {
        val t = input.trim().lowercase().replace("_", "").replace(",", "")
        if (t.isEmpty()) return null
        val (radix, digits) = when {
            fromRadix in 2..36 -> fromRadix to t
            t.startsWith("0x") -> 16 to t.substring(2)
            t.startsWith("0b") -> 2 to t.substring(2)
            t.startsWith("0o") -> 8 to t.substring(2)
            else -> 10 to t
        }
        if (digits.isEmpty()) return null
        val v = runCatching { java.lang.Long.parseUnsignedLong(digits, radix) }
            .getOrElse { return null }
        return view(v)
    }

    /** 数值到四进制视图（Unsigned 全位展示，负数按补码给位账）。 */
    fun view(value: Long): RadixView {
        val u = value
        val bin = java.lang.Long.toBinaryString(u)
        val padded = bin.padStart(64, '0')
        val grouped = padded.chunked(8).joinToString(" ")
        return RadixView(
            value = u,
            bin = java.lang.Long.toUnsignedString(u, 2),
            oct = java.lang.Long.toUnsignedString(u, 8),
            dec = java.lang.Long.toUnsignedString(u, 10),
            hex = String.format("%016x", u),
            bits = grouped,
        )
    }

    /** 有符号解释（同一串位的另一副面孔）。 */
    fun asSigned(bits: Long): String = bits.toString()

    /** 浮点分解：符号/指数/尾数 + 十六进制位。double 版。 */
    data class FloatView(
        val value: Double,
        val sign: Int,
        val exponentBits: String,
        val mantissaBits: String,
        val hexBits: String,
        val asFloat: Float,
    )

    fun floatView(v: Double): FloatView {
        val bits = java.lang.Double.doubleToLongBits(v)
        val sign = (bits ushr 63).toInt()
        val exp = (bits ushr 52) and 0x7FF
        val mant = bits and 0xFFFFFFFFFFFFFL
        return FloatView(
            value = v,
            sign = sign,
            exponentBits = java.lang.Long.toBinaryString(exp).padStart(11, '0'),
            mantissaBits = java.lang.Long.toBinaryString(mant).padStart(52, '0'),
            hexBits = String.format("%016x", bits),
            asFloat = v.toFloat(),
        )
    }


    /** 分卷数：ceil(total/chunk)——纯账，边界（0/1/整除/超过）测试钉住。 */
    fun chunkCount(total: Long, chunk: Long): Int =
        if (total <= 0) 0 else ((total + chunk - 1) / chunk).toInt().coerceAtLeast(1)

    /** 字节数的人话（B/KB/MB/GB）。 */
    fun humanBytes(n: Long): String = when {
        n < 1024 -> "${n}B"
        n < 1024 * 1024 -> "%.1fKB".format(n / 1024.0)
        n < 1024L * 1024 * 1024 -> "%.1fMB".format(n / 1024.0 / 1024.0)
        else -> "%.2fGB".format(n / 1024.0 / 1024.0 / 1024.0)
    }

    /** 用不到但留一个显式取 abs 的口子，防止调用方自己再引第三方。 */
    fun absLong(v: Long): Long = abs(v)
}
