package com.hualuo.engine.search

import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** 一条搜索结果：标题、直链、摘要。三个字段在构造前都清洗净化过，界面拿去就能摆。 */
data class WebSearchResult(val title: String, val url: String, val snippet: String)

/** 搜索收场：要么真结果，要么一句能行动的失败理由。绝不拿空列表冒充「搜完了没结果」。 */
sealed class SearchOutcome {
    data class Ok(val query: String, val results: List<WebSearchResult>) : SearchOutcome()
    data class Failed(val reason: String) : SearchOutcome()
}

/**
 * 网页搜索（免费档：DuckDuckGo 的 HTML 端点，无密钥、无账号）。
 *
 * 范围诚实说清：**免费档起步**这一刀只做 DDG 一家；可配 base URL/key 的付费档
 * （各家返回形状不一，得逐家适配）缓一步再接，不虚账。
 *
 * 设计要点：
 *  - 取网动作经 [fetch] 缝隙注入：纯 JVM 测试喂假页，绝不碰真网；真网由
 *    [defaultFetcher] 负责（连接/读取都有超时，正文有界，不许一页 HTML 撑爆内存）；
 *  - **失败要能行动**：连不上、被限流、页面形状变了，各给各的话——「零结果」若
 *    出现在非空页面上按失败算，不冒充「搜完了没有」；
 *  - 解析是**防御式**的：DDG 的结果链接包在跳转里（uddg= 参数装着真地址），要解码；
 *    标题/摘要里可能混着标签与 HTML 实体，一律清净；认不出的链接整条跳过，不硬凑数。
 */
class WebSearchClient(
    private val fetch: (String) -> String = { url -> defaultFetcher(url) },
    private val maxResults: Int = 8,
) {

    /** 搜一把。任何收场都不抛异常——失败也在 [SearchOutcome] 里说清。 */
    fun search(query: String): SearchOutcome {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return SearchOutcome.Failed("没给搜索词")
        val body = try {
            fetch(BASE_URL + URLEncoder.encode(trimmed, "UTF-8"))
        } catch (e: Exception) {
            return SearchOutcome.Failed(
                "搜索连不上（${e.message ?: "网络出错了"}）：免费档走 DuckDuckGo，可能临时被限流，稍后再试",
            )
        }
        if (body.isBlank()) {
            return SearchOutcome.Failed("对方回了空页（多半是被限流）：稍后再试")
        }
        val results = extract(body)
        if (results.isEmpty()) {
            return SearchOutcome.Failed("这次没有搜到结果（页面形状变了或被限流）：不冒充成功，换个词或稍后再试")
        }
        return SearchOutcome.Ok(trimmed, results.take(maxResults))
    }

    /**
     * 从 DDG HTML 里提结果。链接与摘要分两趟取，按下标配对（数量对不齐就少配几个，
     * 不硬凑）；跳转链接解出真地址，认不出的整条跳过。
     */
    private fun extract(body: String): List<WebSearchResult> {
        val anchors = RESULT_ANCHOR.findAll(body).map { it.value }.toList()
        if (anchors.isEmpty()) return emptyList()
        val snippets = RESULT_SNIPPET.findAll(body).map { cleanText(it.value) }.toList()
        val out = ArrayList<WebSearchResult>(anchors.size)
        anchors.forEachIndexed { index, anchorHtml ->
            val rawHref = HREF.find(anchorHtml)?.groupValues?.get(1) ?: return@forEachIndexed
            val url = decodeLink(rawHref) ?: return@forEachIndexed
            val inner = anchorHtml.substringAfter('>').substringBeforeLast("</a>")
            val title = cleanText(inner)
            if (title.isEmpty()) return@forEachIndexed
            val snippet = snippets.getOrElse(index) { "" }
            out.add(WebSearchResult(title, url, snippet))
        }
        return out
    }

    /**
     * DDG 的结果链接长这样：`//duckduckgo.com/l/?uddg=<URL 编码的真地址>&rut=...`。
     * 真地址在 uddg 参数里，要解码；也有直出 http(s) 的形状，原样收。协议相对的补 https。
     */
    private fun decodeLink(raw: String): String? {
        val candidate = if (raw.contains("uddg=")) {
            val packed = raw.substringAfter("uddg=").substringBefore('&')
            try {
                URLDecoder.decode(packed, "UTF-8")
            } catch (e: Exception) {
                return null
            }
        } else {
            raw
        }
        val fixed = when {
            candidate.startsWith("//") -> "https:$candidate"
            else -> candidate
        }
        return if (fixed.startsWith("http://") || fixed.startsWith("https://")) fixed else null
    }

    /** 去标签、解最常见的一批实体、折白。不清净的字符不进界面。 */
    private fun cleanText(raw: String): String {
        var t = raw.replace(TAG, " ")
        t = t.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
        return t.replace(WHITE, " ").trim()
    }

    companion object {
        /** 免费档端点：DDG 的 HTML 版（lite/html 两个形状里这个结果标记最稳）。 */
        const val BASE_URL = "https://html.duckduckgo.com/html/?q="

        private val RESULT_ANCHOR = Regex("<a\\b[^>]*class=\"result__a\"[^>]*>.*?</a>", RegexOption.DOT_MATCHES_ALL)
        private val RESULT_SNIPPET = Regex("<a\\b[^>]*class=\"result__snippet\"[^>]*>.*?</a>", RegexOption.DOT_MATCHES_ALL)
        private val HREF = Regex("href=\"([^\"]*)\"")
        private val TAG = Regex("<[^>]*>")
        private val WHITE = Regex("\\s+")

        /**
         * 真网取页：超时齐备（连接 10s、读 15s），正文有界（52 万字符封顶，一页 HTML
 * 远够不到）。非 2xx 抛 IOException 带状态码，由 [search] 翻成人话。
         * 默认 java UA 会被不少站拒，报个普通浏览器的。
         */
        fun defaultFetcher(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36")
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val stream = if (code in 300..399 || code in 200..299) conn.inputStream else conn.errorStream
                val reader = InputStreamReader(stream, Charsets.UTF_8)
                val out = StringBuilder()
                val buf = CharArray(8 * 1024)
                while (out.length < FETCH_CHAR_CAP) {
                    val n = reader.read(buf, 0, minOf(buf.size, FETCH_CHAR_CAP - out.length))
                    if (n < 0) break
                    out.append(buf, 0, n)
                }
                return out.toString()
            } finally {
                conn.disconnect()
            }
        }

        /** 正文封顶：52 万字符，防一页巨 HTML 撑爆内存（有界读取是全仓纪律）。 */
        const val FETCH_CHAR_CAP = 512_000
    }
}
