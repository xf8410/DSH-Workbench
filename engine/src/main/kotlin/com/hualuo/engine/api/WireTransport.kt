package com.hualuo.engine.api

import com.hualuo.engine.http.HttpTaxonomy
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * HTTP 换一次的**契约**与 JDK 实现。
 *
 * 为什么走 HttpURLConnection 而不引 OkHttp（D-03 精神：依赖最少）：
 * SSE 逐行读流、封顶计数这些 BoundedWireRead 已经用纯 JDK 写好了，
 * 引一整个 HTTP 库只为 openConnection 不划算；而且少一个依赖少一处供应链风险。
 *
 * 超时策略直说（旧仓的教训钉在这里）：`readTimeout` 是**两次字节之间**的最长等待，
 * 不是总时长 —— 旧仓为了长思考不被误杀把它归零，结果死连接永不报错（见 IdleWatchdog 头注）。
 * 归零是错、砍太短也是错；正确姿势是把它对齐看门狗档位（默认 5 分钟，一个字节都等不到才炸）。
 * 所以走 [effectiveReadTimeoutMs]：**传 0 或负数会被顶回默认档，不许归零**（有测试钉着）。
 */
data class WireRequest(
    val url: String,
    val method: String = "GET",
    val headers: List<Pair<String, String>> = emptyList(),
    /** 请求体全文（JSON 文本）；GET 之类的留 null。 */
    val body: String? = null,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = READ_TIMEOUT_FLOOR_MS,
) {
    /** 真正生效的单次读超时：非正数一律顶回默认档（禁归零是行为，不是注释愿望）。 */
    fun effectiveReadTimeoutMs(): Int =
        if (readTimeoutMs <= 0) READ_TIMEOUT_FLOOR_MS else readTimeoutMs

    companion object {
        /** 连接建立超时默认档。 */
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000

        /** 单次读超时最低档：与 IdleWatchdog.GENERATION_IDLE_MS 对齐。 */
        const val READ_TIMEOUT_FLOOR_MS = 300_000
    }
}

/** 换一次的结果快照。都是事实，不含判断。 */
data class WireResponse(
    val status: Int,
    /** Retry-After 头换算的毫秒；没给就 null。 */
    val retryAfterMs: Long?,
    /**
     * 交付给 [LineSink] 的 2xx 正文字节数。**错误体不计** ——
     * 「收到过内容就不许盲重」这条红线数的是内容，不能被一坨错误 HTML 触发。
     */
    val bytesReceived: Long,
    /** 2xx 为 null（正文已逐行交付）；非 2xx 是截断后的错误体全文。 */
    val errorBody: String?,
)

/** 流中途断开的统一异常：必须带上「断开前已经读到几字节」，红线判定才有据可查。 */
class WireStreamIOException(
    val bytesSoFar: Long,
    cause: Throwable,
) : IOException("流中断：断开前已读 $bytesSoFar 字节", cause)

/** 逐行交付。返回 false 表示接收方要提前收线（比如读到 DONE 收尾标记）。 */
fun interface LineSink {
    fun onLine(line: String): Boolean
}

/** 一次换的抽象：单测用假实现排脚本，生产走 [UrlConnTransport]。 */
interface WireTransport {
    /**
     * 发一次请求并逐行喂 [sink]。本地失败抛 IOException（流断开包成 [WireStreamIOException]）；
     * 状态码非 2xx **不算异常**，装进 [WireResponse] 由上层决策。
     */
    @Throws(IOException::class)
    fun exchange(request: WireRequest, sink: LineSink): WireResponse

    /** 打断进行中的一换（幂等，可跨线程）。 */
    fun cancel()

    /** 是否已被取消：接线层用它区分「流断了」与「用户停了」，别把停止报成失败。 */
    fun isCancelled(): Boolean
}

/** JDK HttpURLConnection 实现。 */
class UrlConnTransport : WireTransport {

    @Volatile private var active: HttpURLConnection? = null
    @Volatile private var cancelled = false

    override fun exchange(request: WireRequest, sink: LineSink): WireResponse {
        cancelled = false
        if (request.url.isBlank()) throw IOException("URL 是空的，没发出去")
        val conn = (URL(request.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            useCaches = false
            connectTimeout = request.connectTimeoutMs.coerceIn(1, 120_000)
            readTimeout = request.effectiveReadTimeoutMs()
            requestMethod = request.method
        }
        active = conn
        try {
            request.headers.forEach { (name, value) -> conn.addRequestProperty(name, value) }
            request.body?.let { text ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val retryAfterMs = parseRetryAfterMs(conn.getHeaderField("Retry-After"))
            if (status in 200..299) {
                val raw = conn.inputStream ?: return WireResponse(status, retryAfterMs, 0L, null)
                val counter = CountingStream(raw)
                val reader = BoundedLineReader(counter)
                try {
                    while (true) {
                        val line = reader.nextLine() ?: break
                        if (!sink.onLine(line)) break
                    }
                } catch (e: WireLimitException) {
                    throw e
                } catch (e: IOException) {
                    throw WireStreamIOException(counter.count, e)
                }
                return WireResponse(status, retryAfterMs, counter.count, null)
            }
            // 非 2xx：错误体整份封顶读；读不动就只给状态码，事实少说但绝不谎报。
            val stream = runCatching { conn.errorStream ?: conn.inputStream }.getOrNull()
            val errorText = stream?.let {
                runCatching { readBoundedText(it, ERROR_BODY_LIMIT_BYTES) }
                    .getOrNull()
                    ?.let { full -> HttpTaxonomy.truncateProviderMessage(full) }
            }
            return WireResponse(status, retryAfterMs, 0L, errorText)
        } finally {
            runCatching { conn.disconnect() }
            active = null
        }
    }

    override fun cancel() {
        cancelled = true
        active?.let { runCatching { it.disconnect() } }
    }

    override fun isCancelled(): Boolean = cancelled

    /** 只加计数字节的透传流。 */
    private class CountingStream(private val src: InputStream) : InputStream() {
        @Volatile var count: Long = 0L
            private set

        override fun read(): Int = src.read().also { if (it >= 0) count += 1 }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            src.read(b, off, len).also { if (it > 0) count += it }

        override fun available(): Int = src.available()

        override fun close() = src.close()
    }

    companion object {
        private const val ERROR_BODY_LIMIT_BYTES = 1_048_576L

        /** Retry-After 认两种写法：秒数、HTTP-date（换算成还要等多久，负数钳到 0）。 */
        internal fun parseRetryAfterMs(header: String?): Long? {
            if (header.isNullOrBlank()) return null
            header.trim().toLongOrNull()?.let { return it * 1_000L }
            runCatching {
                val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(header.trim())
                    ?: return null
                return (date.time - System.currentTimeMillis()).coerceAtLeast(0L)
            }
            return null
        }
    }
}
