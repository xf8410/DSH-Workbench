package com.hualuo.engine.vision

import java.net.URI

/**
 * 视频 URL 准入与分类（analyze_video_url 工具的安检件，纯 JVM 全测）。
 *
 * 准入原则（对齐审查意见）：
 *  - 只认 http/https；内网、回环、云元数据地址一律拒（SSRF 防线）；
 *  - 放行两类：**YouTube 链接**（Gemini 官方支持 fileUri 直读）与
 *    **直接视频文件 URL**（.mp4/.webm/.mov/.m4v/.mkv 结尾）；
 *  - 其余（普通网页、平台分享页、HLS）第一版明确拒，不假装「我去看一下」
 *    然后偷偷下载伪造总结。
 */
object VideoUrlPolicy {

    enum class Kind { YOUTUBE, DIRECT_FILE }

    sealed class Verdict {
        data class Allowed(val kind: Kind) : Verdict()
        data class Rejected(val reason: String) : Verdict()
    }

    private val DIRECT_EXTENSIONS = listOf("mp4", "webm", "mov", "m4v", "mkv")

    fun check(url: String): Verdict {
        val trimmed = url.trim()
        val uri = runCatching { URI(trimmed) }.getOrElse {
            return Verdict.Rejected("这不是一个能解析的 URL：$trimmed")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Verdict.Rejected("只允许 http/https 链接（来了个「$scheme」）")
        }
        val host = uri.host?.lowercase() ?: return Verdict.Rejected("URL 里没有主机名")
        if (isPrivateHost(host)) {
            return Verdict.Rejected("内网/回环/元数据地址不放行（$host）")
        }
        return when {
            isYouTube(host, uri.path?.lowercase().orEmpty()) -> Verdict.Allowed(Kind.YOUTUBE)
            isDirectFile(uri.path?.lowercase().orEmpty()) -> Verdict.Allowed(Kind.DIRECT_FILE)
            else -> Verdict.Rejected(
                "这是网页或平台分享页，不是直接视频地址。第一版只认 YouTube 链接与直接视频文件 URL（.mp4/.webm/.mov/.m4v/.mkv 结尾）",
            )
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host == "0.0.0.0" || host == "::1" || host == "[::1]") return true
        val ip = host.removeSurrounding("[", "]")
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size == 4) {
            val (a, b) = parts
            if (a == 127 || a == 10 || a == 0) return true
            if (a == 192 && b == 168) return true
            if (a == 172 && b in 16..31) return true
            if (a == 169 && b == 254) return true // 云元数据 169.254.169.254 在此段
        }
        if (ip.startsWith("fc") || ip.startsWith("fd") || ip == "::") return true // IPv6 ULA/未指定
        return false
    }

    private fun isYouTube(host: String, path: String): Boolean =
        (host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" || host == "m.youtube.com") &&
            (path.startsWith("/watch") || path.startsWith("/shorts") || host == "youtu.be")

    private fun isDirectFile(path: String): Boolean {
        val clean = path.substringBefore('?').substringBefore('#')
        return DIRECT_EXTENSIONS.any { clean.endsWith(".$it") }
    }
}
