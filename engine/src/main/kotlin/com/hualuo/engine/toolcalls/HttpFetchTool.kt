package com.hualuo.engine.toolcalls

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 通用 HTTP 取件（P1 补）：`http_head` 看对方有什么，`http_download` 把文件落到手机。
 *
 * 为什么现在补：这几轮为了拿 GitHub 的构建产物，每次都得现写一段 node 脚本
 * （跟随 302、限制大小、算校验）。这段逻辑属于「Agent 的基本手脚」，不该每轮手搓。
 *
 * 纪律：
 *  - 只认 http/https，file:// 一律拒（不许借下载读本地文件）；
 *  - 跟随重定向最多 5 跳，**每跳都重新断言协议**（防 302 到 file://）；
 *  - 下载流式写盘，内存里只有一个 64KB 块；超上限就删半截文件再报（半个包比没有更坑）；
 *  - 目标路径必须绝对、不许含 `..`，目录不存在就建；
 *  - 不发送任何认证头：通用下载不做令牌透传（GitHub 的令牌路径在 github_* 族里单走）。
 */
object HttpFetchTool {

    /** 单次下载默认上限：200MB。 */
    const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024

    /** 重定向最多跟几跳。 */
    const val MAX_REDIRECTS = 5

    fun register(registry: ToolRegistry) {
        registry.register(
            ToolSpec(
                name = "http_head",
                description = "看一个网址的响应头：状态码、内容类型、大小、最终地址（跟随 302）。" +
                    "下载前先确认对方给的是什么，别盲下大文件。只读，不落盘",
                parametersJson = """{"type":"object","properties":{"url":{"type":"string","description":"http/https 网址"}},"required":["url"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val url = reqUrl(args, "url")
            formatHead(probe(url))
        }

        registry.register(
            ToolSpec(
                name = "http_download",
                description = "把一个网址的文件下到手机本地（流式，上限默认 200MB）。" +
                    "path 要绝对路径，如 /sdcard/Download/x.zip；已存在会覆盖",
                parametersJson = """{"type":"object","properties":{"url":{"type":"string","description":"http/https 网址"},"path":{"type":"string","description":"保存到的绝对路径"},"max_mb":{"type":"integer","description":"大小上限（MB），默认 200，范围 1-2048"}},"required":["url","path"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val url = reqUrl(args, "url")
            val target = targetFile(reqStr(args, "path", "绝对路径，如 /sdcard/Download/x.zip"))
            val maxMb = (optInt(args, "max_mb") ?: 200).coerceIn(1, 2048)
            val maxBytes = maxMb.toLong() * 1024 * 1024
            val result = fetchToFile(url, target, maxBytes)
            val problem = result.error
            if (problem != null) fail(problem)
            buildString {
                append("已下载：").append(result.path)
                append("\n- 大小：").append(result.bytes).append(" 字节（").append(mb(result.bytes)).append("）")
                append("\n- SHA-256：").append(result.sha256)
                if (result.finalUrl != url) append("\n- 最终地址：").append(result.finalUrl)
                append("\n- 重定向跳数：").append(result.redirects)
            }
        }
    }

    // ── 网络 ───────────────────────────────────────────────────────

    data class HeadInfo(
        val status: Int,
        val finalUrl: String,
        val contentType: String,
        val contentLength: Long,
        val redirects: Int,
        val error: String?,
    )

    data class DownloadResult(
        val path: String,
        val bytes: Long,
        val sha256: String,
        val finalUrl: String,
        val redirects: Int,
        val error: String?,
    )

    /** HEAD 探测；服务器不认 HEAD（405/501）时退回 GET + Range 只看第一字节。 */
    fun probe(url: String): HeadInfo {
        val first = try {
            open(url, "HEAD")
        } catch (e: Exception) {
            return HeadInfo(0, url, "", 0, 0, "请求没发出去：${e.message ?: "内部出错"}")
        }
        var conn = first.second
        var current = first.first
        var redirects = 0
        try {
            var status = conn.responseCode
            while (status in 300..399 && redirects < MAX_REDIRECTS) {
                val location = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                current = resolve(current, location)
                conn = open(current, "HEAD").second
                status = conn.responseCode
                redirects += 1
            }
            if (status == 405 || status == 501) {
                conn.disconnect()
                val getConn = open(current, "GET").second
                getConn.setRequestProperty("Range", "bytes=0-0")
                val getStatus = getConn.responseCode
                val result = HeadInfo(
                    status = getStatus,
                    finalUrl = current,
                    contentType = getConn.contentType ?: "",
                    contentLength = getConn.contentLengthLong,
                    redirects = redirects,
                    error = null,
                )
                getConn.disconnect()
                return result
            }
            return HeadInfo(
                status = status,
                finalUrl = current,
                contentType = conn.contentType ?: "",
                contentLength = conn.contentLengthLong,
                redirects = redirects,
                error = if (status in 200..399) null else "对方回了 $status",
            )
        } catch (e: Exception) {
            return HeadInfo(0, current, "", 0, redirects, "读取响应头失败：${e.message ?: "内部出错"}")
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    fun fetchToFile(url: String, target: File, maxBytes: Long): DownloadResult {
        var current = url
        var redirects = 0
        var conn: HttpURLConnection? = null
        try {
            while (true) {
                conn = open(current, "GET").second
                val status = conn.responseCode
                if (status in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) {
                        return DownloadResult(target.path, 0, "", current, redirects, "对方回了 $status 却没给跳转地址")
                    }
                    if (redirects >= MAX_REDIRECTS) {
                        return DownloadResult(target.path, 0, "", current, redirects, "重定向超过 $MAX_REDIRECTS 跳：不给跟了")
                    }
                    current = resolve(current, location)
                    redirects += 1
                    continue
                }
                if (status !in 200..299) {
                    val body = conn.errorStream?.use { it.readBytes().decodeToString().take(200) } ?: ""
                    conn.disconnect()
                    return DownloadResult(target.path, 0, "", current, redirects, "对方回了 $status：$body")
                }
                target.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) {
                                output.flush()
                                conn.disconnect()
                                runCatching { target.delete() }
                                return DownloadResult(
                                    target.path, 0, "", current, redirects,
                                    "文件比上限 ${maxBytes / (1024 * 1024)}MB 还大（已删掉半截文件）：调大 max_mb 或换个小文件",
                                )
                            }
                            digest.update(buf, 0, n)
                            output.write(buf, 0, n)
                        }
                    }
                }
                conn.disconnect()
                return DownloadResult(target.path, total, hex(digest.digest()), current, redirects, null)
            }
        } catch (e: Exception) {
            runCatching { conn?.disconnect() }
            runCatching { target.delete() }
            return DownloadResult(target.path, 0, "", current, redirects, "下载没写成：${e.message ?: "内部出错"}（半截文件已删）")
        }
    }

    private fun open(url: String, method: String): Pair<String, HttpURLConnection> {
        requireScheme(url)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = false
        conn.requestMethod = method
        conn.setRequestProperty("user-agent", "hualuo-repo-tool")
        return url to conn
    }

    private fun resolve(base: String, location: String): String =
        if (location.startsWith("http://") || location.startsWith("https://")) location
        else URL(URL(base), location).toString()

    private fun requireScheme(url: String) {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw IllegalArgumentException("只认 http/https 网址（现在是「$url」）")
        }
    }

    // ── 结果与参数 ─────────────────────────────────────────────────

    private fun formatHead(info: HeadInfo): String {
        val error = info.error
        if (error != null) fail(error)
        val size = if (info.contentLength >= 0) "${info.contentLength} 字节（${mb(info.contentLength)}）" else "对方没给大小"
        return buildString {
            append("HTTP ").append(info.status)
            append("\n- 内容类型：").append(info.contentType.ifEmpty { "未声明" })
            append("\n- 大小：").append(size)
            if (info.redirects > 0) append("\n- 跳转 ").append(info.redirects).append(" 次到 ").append(info.finalUrl)
        }
    }

    private fun mb(bytes: Long): String = String.format("%.1fMB", bytes / (1024.0 * 1024))

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        bytes.forEach { b -> sb.append("0123456789abcdef"[(b.toInt() shr 4) and 0xF]).append("0123456789abcdef"[b.toInt() and 0xF]) }
        return sb.toString()
    }

    private fun reqUrl(args: JsonObject, key: String): String {
        val url = optStr(args, key) ?: fail("缺参数 $key（http/https 网址）")
        if (!url.lowercase().startsWith("http://") && !url.lowercase().startsWith("https://")) {
            fail("$key 只认 http/https 网址（现在是「$url」）")
        }
        return url
    }

    private fun targetFile(raw: String): File {
        val path = raw.trim()
        if (!path.startsWith("/")) fail("path 要是绝对路径（如 /sdcard/Download/x.zip）")
        if (path.contains("..")) fail("path 里不许有 ..（防写到别处）")
        return File(path)
    }

    private fun argsOf(argumentsJson: String): JsonObject =
        runCatching { json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: JsonObject(emptyMap())

    private fun optStr(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun reqStr(args: JsonObject, key: String, hint: String): String =
        optStr(args, key) ?: fail("缺参数 $key（$hint）")

    private fun optInt(args: JsonObject, key: String): Int? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
}
