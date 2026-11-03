package com.hualuo.engine.toolcalls

import com.hualuo.engine.search.SearchOutcome
import com.hualuo.engine.search.WebSearchClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 网页工具族（M4 第五刀，语义对齐旧 Agora WebSearchToolProvider）：两件——
 *  - web_search：搜索（吃 [WebSearchClient]，免费档 DDG；num_results 1-10 默认 5）；
 *  - web_fetch：取网页转正文（HTML 净化后按**文本**截断，不是砍 HTML——
 *    maxChars 默认 8000、封顶 10 万；回执带 truncated + totalChars，模型可加量再取）。
 *
 * 可见性走 [ToolRegistry.register] 的 visibleIf（设置开关关掉=清单里消失），
 * 不是「看得见但点不动」——对齐旧仓 definitions(ctx) 按开关返空。
 *
 * 取网动作全部缝隙注入（[searchClient] 的 fetch、本件的 [fetcher]）：纯 JVM 测试不碰真网。
 * 零脱敏：抓到什么转什么，正文原文进出。
 */
object WebTool {

    private const val DEFAULT_MAX_CHARS = 8_000
    private const val CAP_MAX_CHARS = 100_000
    private const val DEFAULT_RESULTS = 5

    fun register(
        registry: ToolRegistry,
        searchClient: WebSearchClient,
        fetcher: (String) -> String = { url -> WebSearchClient.defaultFetcher(url) },
        visibleIf: (() -> Boolean)? = null,
    ) {
        // 注意不写 register(spec) { ... } 尾随 lambda：会绑到 registerGated 的 visibleIf 上（仓里注释警告过的坑）
        val searchSpec = ToolSpec(
            name = "web_search",
            description = "搜索网页查实时资料（新闻、版本号、文档——训练集里没有的都搜这里）。",
            parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"搜索词"},"num_results":{"type":"integer","description":"返回条数 1-10，默认 5"}},"required":["query"]}""",
        )
        val searchHandler = ToolHandler { argumentsJson -> doSearch(searchClient, argumentsJson) }
        val fetchSpec = ToolSpec(
            name = "web_fetch",
            description = "取一个网页并转成可读正文（搜索结果想看全文时用）。maxChars 默认 8000，截断会在回执里说明。",
            parametersJson = """{"type":"object","properties":{"url":{"type":"string","description":"要取的网址"},"maxChars":{"type":"integer","description":"正文最多取多少字符（1-100000，默认 8000）"}},"required":["url"]}""",
        )
        val fetchHandler = ToolHandler { argumentsJson -> doFetch(fetcher, argumentsJson) }
        if (visibleIf == null) {
            registry.register(searchSpec, searchHandler)
            registry.register(fetchSpec, fetchHandler)
        } else {
            registry.registerGated(searchSpec, searchHandler, visibleIf)
            registry.registerGated(fetchSpec, fetchHandler, visibleIf)
        }
    }

    // ---------- web_search ----------

    private fun doSearch(client: WebSearchClient, argumentsJson: String): String {
        val args = argsOf(argumentsJson)
        val query = (args["query"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) return """{"type":"web_search","error":"no_query"}"""
        val num = ((args["num_results"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: DEFAULT_RESULTS)
            .coerceIn(1, 10)
        return when (val outcome = client.search(query)) {
            is SearchOutcome.Ok -> {
                val rows = outcome.results.take(num).joinToString(",") { r ->
                    """{"title":${JsonPrimitive(r.title)},"url":${JsonPrimitive(r.url)},"description":${JsonPrimitive(r.snippet)}}"""
                }
                """{"type":"web_search","query":${JsonPrimitive(query)},"count":${minOf(num, outcome.results.size)},"results":[$rows]}"""
            }
            is SearchOutcome.Failed ->
                """{"type":"web_search","query":${JsonPrimitive(query)},"error":"search_error","message":${JsonPrimitive(outcome.reason)}}"""
        }
    }

    // ---------- web_fetch ----------

    private fun doFetch(fetcher: (String) -> String, argumentsJson: String): String {
        val args = argsOf(argumentsJson)
        val url = (args["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (url.isEmpty()) return """{"type":"web_fetch","error":"no_url"}"""
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return """{"type":"web_fetch","url":${JsonPrimitive(url)},"error":"bad_url","message":"只认 http/https"}"""
        }
        val maxChars = ((args["maxChars"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: DEFAULT_MAX_CHARS)
            .coerceIn(1, CAP_MAX_CHARS)
        return try {
            val html = fetcher(url)
            val fullText = htmlToReadableText(html)
            val text = fullText.take(maxChars)
            """{"type":"web_fetch","url":${JsonPrimitive(url)},"text":${JsonPrimitive(text)},"truncated":${fullText.length > text.length},"totalChars":${fullText.length}}"""
        } catch (e: Exception) {
            """{"type":"web_fetch","url":${JsonPrimitive(url)},"error":"fetch_error","message":${JsonPrimitive(e.message ?: "内部出错")}}"""
        }
    }

    /**
     * HTML 转可读正文（对齐旧仓 htmlToReadableText，纯 JVM 版）：
     * 先剥注释与无信息块（script/style/noscript/svg/head/nav/header/footer/aside——
     * 导航菜单页脚不许吃掉正文的字数预算），再去标签、解实体、折白。
     */
    internal fun htmlToReadableText(rawHtml: String): String {
        var stripped = rawHtml
            .replace(Regex("<!--[\\s\\S]*?-->"), " ")
            .replace(Regex("<(script|style|noscript|svg|head)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<(nav|header|footer|aside)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
        stripped = stripped.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(p|div|li|h[1-6]|tr|blockquote|pre)>", RegexOption.IGNORE_CASE), "\n")
        val text = stripped.replace(Regex("<[^>]*>"), " ")
        val decoded = decodeEntities(text)
        return decoded
            .replace(Regex("[ \\t\\x0B\\u000C\\r]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** 实体解码：命名的常见批 + 数字（十进制/十六进制）。纯 JVM，不依赖 HtmlCompat。 */
    private fun decodeEntities(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i + 1)
                if (semi > i && semi - i <= 12) {
                    val entity = s.substring(i + 1, semi)
                    val named = NAMED[entity]
                    if (named != null) {
                        out.append(named)
                        i = semi + 1
                        continue
                    }
                    val numeric = when {
                        entity.startsWith("#x") || entity.startsWith("#X") ->
                            entity.substring(2).toIntOrNull(16)
                        entity.startsWith("#") -> entity.substring(1).toIntOrNull()
                        else -> null
                    }
                    if (numeric != null && numeric in 0..0x10FFFF) {
                        out.append(numeric.toChar())
                        i = semi + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "mdash" to "\u2014", "ndash" to "\u2013", "hellip" to "\u2026",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "ldquo" to "\u201C", "rdquo" to "\u201D",
        "copy" to "\u00A9", "reg" to "\u00AE", "middot" to "\u00B7",
        "bull" to "\u2022", "deg" to "\u00B0", "plusmn" to "\u00B1", "times" to "\u00D7",
        "laquo" to "\u00AB", "raquo" to "\u00BB", "eacute" to "\u00E9", "egrave" to "\u00E8",
        "agrave" to "\u00E0", "ccedil" to "\u00E7", "uuml" to "\u00FC", "ouml" to "\u00F6",
        "auml" to "\u00E4", "szlig" to "\u00DF",
    )

    // ---------- 内部件 ----------

    private fun argsOf(argumentsJson: String): JsonObject =
        runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: JsonObject(emptyMap())
}
