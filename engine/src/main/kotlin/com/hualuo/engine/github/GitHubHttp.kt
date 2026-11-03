package com.hualuo.engine.github

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub HTTP 的共用小件（GitHubCiClient 与 GitHubRepoClient 双客户端共一个实现，防双源坑）。
 *
 * 安全规矩：令牌只进请求头，绝不进任何报错、日志与界面文本——错误里只带状态码；
 * 响应有界读（512KB 封顶），GitHub 错误页再大也只取前一段；
 * 被截断的响应必须带 truncated 标记出来，不许当完整账用。
 */
const val GITHUB_API_ROOT = "https://api.github.com"

/** 默认 JSON 档 accept；拉文件原文时传 application/vnd.github.raw。 */
const val GITHUB_ACCEPT_RAW = "application/vnd.github.raw"

/** 有界读结果：text 之外带 truncated（读到封顶还没读完 = true）。 */
data class BoundedBody(val text: String, val truncated: Boolean)

/** 有界读：最多 maxChars 字符，超了就停并把 truncated 说出来（不读完整页错误 HTML）。 */
fun readBounded(stream: InputStream, maxChars: Int): BoundedBody {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    var total = 0
    while (total < maxChars) {
        val n = stream.read(buf, 0, minOf(buf.size, maxChars - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    // 读满封顶就当截断（恰好一字节不差的情况极少，宁可多说一句「可能没读全」也不冒充完整）
    return BoundedBody(out.toString("UTF-8"), total >= maxChars)
}

/**
 * 默认 GET 实现：15 秒超时、令牌只进头、异常折成 status=0（body 带原因）。
 * [accept] 缺省走 JSON 档；GitHubRepoClient 读文件原文时传 raw 档。
 * [maxChars] 给读大文件清单的调用方放宽（默认 512K 全仓纪律）。
 *
 * X-OAuth-Scopes 响应头原文一并带出（[GitHubHttpResult.scopes]）：登录验证用它显示
 * 「这枚令牌有什么权限」；没有这个头（细粒度令牌常见）就回空串，空不是失败。
 */
fun githubHttpGet(url: String, token: String?, accept: String? = null, maxChars: Int = GITHUB_MAX_BODY_CHARS): GitHubHttpResult = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 15_000
    conn.requestMethod = "GET"
    conn.setRequestProperty("accept", accept ?: "application/vnd.github+json")
    conn.setRequestProperty("user-agent", "hualuo-repo-tool")
    if (!token.isNullOrBlank()) conn.setRequestProperty("authorization", "Bearer $token")
    val status = conn.responseCode
    val scopes = conn.getHeaderField("X-OAuth-Scopes").orEmpty()
    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
    val body = stream?.use { readBounded(it, maxChars) }
    GitHubHttpResult(status, body?.text ?: "", body?.truncated ?: false, scopes)
} catch (e: IOException) {
    GitHubHttpResult(0, e.message ?: "网络不通", false)
} catch (e: Exception) {
    GitHubHttpResult(0, e.message ?: "请求没发出去", false)
}

/**
 * PUT JSON 实现（contents 改码提交用）：请求体一次性给全（文本量级，不流式），
 * 30 秒读超时（提交比读慢是正常的），其余安全规矩与 GET 相同。
 */
fun githubHttpPutJson(url: String, token: String?, jsonBody: String): GitHubHttpResult = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    conn.requestMethod = "PUT"
    conn.setRequestProperty("accept", "application/vnd.github+json")
    conn.setRequestProperty("content-type", "application/json")
    conn.setRequestProperty("user-agent", "hualuo-repo-tool")
    if (!token.isNullOrBlank()) conn.setRequestProperty("authorization", "Bearer $token")
    val bytes = jsonBody.toByteArray(Charsets.UTF_8)
    conn.doOutput = true
    conn.setFixedLengthStreamingMode(bytes.size)
    conn.outputStream.use { it.write(bytes) }
    val status = conn.responseCode
    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
    val body = stream?.use { readBounded(it, GITHUB_MAX_BODY_CHARS) }
    GitHubHttpResult(status, body?.text ?: "", body?.truncated ?: false)
} catch (e: IOException) {
    GitHubHttpResult(0, e.message ?: "网络不通", false)
} catch (e: Exception) {
    GitHubHttpResult(0, e.message ?: "请求没发出去", false)
}

/** 有界读的封顶：512K 字符（全仓纪律：响应必须有界）。 */
const val GITHUB_MAX_BODY_CHARS = 512_000

/** 与 PUT 同一条纪律的 POST（建 PR 用）：令牌只进头、有界读、异常折成 status=0。 */
fun githubHttpPostJson(url: String, token: String?, jsonBody: String): GitHubHttpResult = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    conn.requestMethod = "POST"
    conn.setRequestProperty("accept", "application/vnd.github+json")
    conn.setRequestProperty("content-type", "application/json")
    conn.setRequestProperty("user-agent", "hualuo-repo-tool")
    if (!token.isNullOrBlank()) conn.setRequestProperty("authorization", "Bearer $token")
    val bytes = jsonBody.toByteArray(Charsets.UTF_8)
    conn.doOutput = true
    conn.setFixedLengthStreamingMode(bytes.size)
    conn.outputStream.use { it.write(bytes) }
    val status = conn.responseCode
    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
    val body = stream?.use { readBounded(it, GITHUB_MAX_BODY_CHARS) }
    GitHubHttpResult(status, body?.text ?: "", body?.truncated ?: false)
} catch (e: IOException) {
    GitHubHttpResult(0, e.message ?: "网络不通", false)
} catch (e: Exception) {
    GitHubHttpResult(0, e.message ?: "请求没发出去", false)
}


/**
 * 流式请求体发送（大文件上传专用，查看器/上传全格式的引擎件）：
 * [writeBody] 直接往连接输出流写——文件流边读边 base64 编码边发，
 * 全程内存里只有几 KB 的块缓冲（红线二：不许把整个文件读进内存）。
 * [contentLength] 必须调用方先算准（base64 长度可预知），分块传输我们不设——
 * GitHub API 不认 chunked 的 JSON。
 */
fun githubHttpSendStreaming(
    url: String,
    token: String?,
    method: String,
    contentLength: Long,
    writeBody: (java.io.OutputStream) -> Unit,
): GitHubHttpResult = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 120_000
    conn.requestMethod = method
    conn.setRequestProperty("accept", "application/vnd.github+json")
    conn.setRequestProperty("content-type", "application/json")
    conn.setRequestProperty("user-agent", "hualuo-repo-tool")
    if (!token.isNullOrBlank()) conn.setRequestProperty("authorization", "Bearer $token")
    conn.doOutput = true
    conn.setFixedLengthStreamingMode(contentLength)
    conn.outputStream.use { writeBody(it) }
    val status = conn.responseCode
    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
    val body = stream?.use { readBounded(it, GITHUB_MAX_BODY_CHARS) }
    GitHubHttpResult(status, body?.text ?: "", body?.truncated ?: false)
} catch (e: IOException) {
    GitHubHttpResult(0, e.message ?: "网络不通", false)
} catch (e: Exception) {
    GitHubHttpResult(0, e.message ?: "请求没发出去", false)
}


/** PATCH JSON 实现（git ref 快进更新用）：与 POST 同规矩，方法换 PATCH。 */
fun githubHttpPatchJson(url: String, token: String?, jsonBody: String): GitHubHttpResult = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    conn.requestMethod = "PATCH"
    conn.setRequestProperty("accept", "application/vnd.github+json")
    conn.setRequestProperty("content-type", "application/json")
    conn.setRequestProperty("user-agent", "hualuo-repo-tool")
    if (!token.isNullOrBlank()) conn.setRequestProperty("authorization", "Bearer $token")
    val bytes = jsonBody.toByteArray(Charsets.UTF_8)
    conn.doOutput = true
    conn.setFixedLengthStreamingMode(bytes.size)
    conn.outputStream.use { it.write(bytes) }
    val status = conn.responseCode
    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
    val body = stream?.use { readBounded(it, GITHUB_MAX_BODY_CHARS) }
    GitHubHttpResult(status, body?.text ?: "", body?.truncated ?: false)
} catch (e: IOException) {
    GitHubHttpResult(0, e.message ?: "网络不通", false)
} catch (e: Exception) {
    GitHubHttpResult(0, e.message ?: "请求没发出去", false)
}
