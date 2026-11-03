package com.hualuo.engine.api

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 读网络响应时的"封顶"工具。**搬自原版 Agora 的 `api/BoundedWireRead.kt`**（那版建在 okio 的
 * `BufferedSource` 上），这里改成纯 JDK `InputStream`：本项目不引新依赖（D-03），
 * 而且封顶的**语义**必须保住 —— 它防的是"一个响应把内存吃光"，跟用什么 IO 库无关。
 *
 * 搬的时候修了两处：
 *
 * 1. **末尾没有换行的一行被当成错误**。原版读 SSE 单行用的是 `readUtf8LineStrict(limit)`，
 *    它在"读到结尾都没碰到换行"时抛 EOFException，原版只分两种情况：缓冲区超上限就报"事件超限"，
 *    否则一律报"行不完整"。可是**连接关闭前的最后一行本来就可能不带换行**（服务端写完就关，
 *    或 JSON 响应末尾没有 `\n`）—— 那是一条合法数据，原版会把它丢掉并报错，
 *    表现就是"回答看着完整了但客户端说流断了"。这里改成：到结尾没有换行、且没超限，**照样把这段交出去**，
 *    下一次再读才返回 null。
 * 2. **错误文案**。原版写的是 "Incomplete encrypted event line"，SSE 行不是加密的，
 *    这句会把排查的人往错方向带（而且这类消息最终会进给你的报错里）。
 *
 * 两处封顶都是**解码后**的字节数：gzip 响应解压完才算，不然压缩比一高就白限了。
 */

/** 超过封顶：单独一个类型，好让上层把它跟"网络断了""对方 401"分开报。 */
class WireLimitException(message: String) : IOException(message)

/** 整份响应体封顶读取：最多 limit 字节，多一个字节就整份拒绝，绝不先攒进内存再判断。 */
fun readBoundedText(input: InputStream, limitBytes: Long, charsetName: String = "UTF-8"): String {
    require(limitBytes > 0 && limitBytes < Long.MAX_VALUE) { "封顶必须是正数且不能是 Long.MAX_VALUE，现在是 $limitBytes" }
    val out = ByteArrayOutputStream(8 * 1024)
    val buf = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buf)
        if (read < 0) break
        total += read
        if (total > limitBytes) {
            throw WireLimitException("响应体超过 $limitBytes 字节上限，已丢弃这 $total 字节，不整份读入内存")
        }
        out.write(buf, 0, read)
    }
    return out.toString(charsetName)
}

/**
 * 一行一行读（SSE 用）。单行封顶 [maxLineBytes]，超了就抛 [WireLimitException] ——
 * 一个不带换行的巨型响应能把逐行读取器撑爆，这条上限就是防它。
 *
 * 用 [nextLine]：正常给一行；**到流末尾还剩没换行的内容也照样给一行**（修掉的那处）；
 * 真没内容了给 null。
 */
class BoundedLineReader(
    private val input: InputStream,
    private val maxLineBytes: Long = DEFAULT_MAX_LINE_BYTES,
) {

    private var eofReached = false

    fun nextLine(): String? {
        require(maxLineBytes > 0 && maxLineBytes < Long.MAX_VALUE) { "单行上限必须是正数" }
        val line = ByteArrayOutputStream(4 * 1024)
        var count = 0L
        var sawAnyByte = false
        while (true) {
            val b = input.read()
            if (b < 0) {
                eofReached = true
                // 到结尾没换行：这段仍是合法数据（原版在这里报错，是 bug）
                return if (sawAnyByte) line.toString("UTF-8").trimLineEnd() else null
            }
            sawAnyByte = true
            if (b == LF) return line.toString("UTF-8").trimLineEnd()
            count += 1
            if (count > maxLineBytes) {
                throw WireLimitException(
                    "单行超过 $maxLineBytes 字节上限（多半是对方没按行发或塞了巨型事件），中止这次读取",
                )
            }
            line.write(b)
        }
    }

    /** 已经读到流末尾了（给上层判断"是没写完还是正常结束"）。 */
    val atEnd: Boolean get() = eofReached

    companion object {
        /** 单行默认封顶 1 MiB：SSE 事件里塞得下最长的合理内容，也拦得住故意的巨行。 */
        const val DEFAULT_MAX_LINE_BYTES = 1_048_576L
        private const val LF = 10
        private const val CR = 13
    }
}

/** 行尾的 `\r` 去掉（CRLF 与 LF 都认），行内内容不动。 */
private fun String.trimLineEnd(): String = if (endsWith("\r")) dropLast(1) else this
