package com.hualuo.engine.github

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP 两个补充动作（P0 十四件用）：二进制 POST（Release 附件上传）与重定向读取
 * （Actions artifact 的 zip 走 302 到第三方对象存储）。
 *
 * 安全规矩与 GitHubHttp.kt 一字不差，另加一条硬规矩：
 *  - 令牌只进**第一跳**的请求头；302 之后的第三方地址**绝不带令牌**——
 *    artifact 的签名 URL 在 objects.githubusercontent.com / pipelines.actions 上，
 *    把 Bearer 令牌转发过去就是泄给第三方；
 *  - 响应有界读，错误体再大也只取前一段；
 *  - 异常一律折成 status=0，不抛穿。
 */
fun githubHttpPostBinary(
    url: String,
    token: String?,
    contentType: String,
    bytes: ByteArray,
): GitHubHttpResult = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 120_000
    conn.requestMethod = "POST"
    conn.setRequestProperty("accept", "application/vnd.github+json")
    conn.setRequestProperty("content-type", contentType)
    conn.setRequestProperty("user-agent", "hualuo-repo-tool")
    if (!token.isNullOrBlank()) conn.setRequestProperty("authorization", "Bearer $token")
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
 * 取一个会 302 的地址的最终下载入口：只做**第一跳**，不跟随、不读 body，
 * 把 Location 原样交回。跟随与落盘由 [githubDownloadToFile] 做（那样才能把令牌摘掉）。
 */
fun githubResolveRedirect(url: String, token: String?): String? = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    conn.requestMethod = "GET"
    conn.instanceFollowRedirects = false
    conn.setRequestProperty("accept", "application/vnd.github+json")
    conn.setRequestProperty("user-agent", "hualuo-repo-tool")
    if (!token.isNullOrBlank()) conn.setRequestProperty("authorization", "Bearer $token")
    val status = conn.responseCode
    val location = conn.getHeaderField("Location")
    conn.disconnect()
    if (status in 300..399) location?.takeIf { it.isNotBlank() } else null
} catch (e: Exception) {
    null
}

/**
 * 把 [url] 流式写到 [target]：内存里只有一个 64KB 的块（红线：不把整包读进内存）。
 *
 * [maxBytes] 是硬上限：超了立刻停手、删掉半截文件、把「超了」说出来——
 * 半个 zip 比没有更坑（解压时才炸）。
 * 这一跳**不带令牌**：签名 URL 自带凭据，带令牌等于把令牌送给第三方存储。
 */
fun githubDownloadToFile(
    url: String,
    target: java.io.File,
    maxBytes: Long,
): Pair<Long, String?> = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 120_000
    conn.requestMethod = "GET"
    conn.setRequestProperty("user-agent", "hualuo-repo-tool")
    val status = conn.responseCode
    if (status !in 200..299) {
        val body = conn.errorStream?.use { readBounded(it, 2_000) }?.text ?: ""
        conn.disconnect()
        return 0L to ghIssue(status, body, "下载")
    }
    target.parentFile?.mkdirs()
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
                    return 0L to "包比上限 ${maxBytes / (1024 * 1024)}MB 还大（已删掉半截文件）：换个小一点的 artifact"
                }
                output.write(buf, 0, n)
            }
        }
    }
    conn.disconnect()
    total to null
} catch (e: Exception) {
    runCatching { target.delete() }
    0L to "下载没写成：${e.message ?: "内部出错"}（半截文件已删）"
}
