package com.hualuo.engine.toolcalls

import com.hualuo.engine.search.WebSearchClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页工具族对照表：全离线（搜索与取页都注入假 fetch）。
 */
class WebToolTest {

    /** 一页假 DDG 结果（WebSearchClient 认的形状）。 */
    private val ddgHtml = """
        <html><body>
        <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fdocs">Example 文档</a>
        <a class="result__snippet" href="#">这是 <b>摘要</b> 一行</a>
        <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fmore">第二条结果</a>
        <a class="result__snippet" href="#">第二条的摘要</a>
        </body></html>
    """.trimIndent()

    @Test
    fun twoToolsRegistered() {
        val r = ToolRegistry()
        WebTool.register(r, WebSearchClient(fetch = { _ -> ddgHtml }), fetcher = { ddgHtml })
        assertEquals(2, r.size())
        assertEquals(listOf("web_search", "web_fetch"), r.specs().map { it.name })
    }

    @Test
    fun searchParsesAndCaps() {
        val r = ToolRegistry()
        WebTool.register(r, WebSearchClient(fetch = { _ -> ddgHtml }), fetcher = { ddgHtml })
        val out = r.execute("web_search", """{"query":"example 文档","num_results":1}""")
        assertTrue(out.ok)
        assertTrue("标题在账：${out.text}", out.text.contains("Example 文档"))
        assertTrue("摘要解了标签：${out.text}", out.text.contains("这是 摘要 一行"))
        assertTrue("只拿一条：${out.text}", !out.text.contains("第二条结果"))
    }

    @Test
    fun fetchExtractsTextAndTruncationAccounting() {
        val body = "<nav>菜单 导航</nav><article><p>第一段正文&amp;有实体</p><p>第二段</p></article><footer>页脚</footer>"
        val r = ToolRegistry()
        WebTool.register(r, WebSearchClient(fetch = { _ -> "" }), fetcher = { _ -> body })
        val out = r.execute("web_fetch", """{"url":"https://example.com/a","maxChars":100000}""")
        assertTrue(out.ok)
        assertTrue("正文在：${out.text}", out.text.contains("第一段正文&有实体"))
        assertTrue("页脚被剥：${out.text}", !out.text.contains("页脚"))
        assertTrue("导航被剥：${out.text}", !out.text.contains("菜单 导航"))
        // 截断账：只许 6 个字
        val cut = r.execute("web_fetch", """{"url":"https://example.com/a","maxChars":6}""")
        assertTrue(cut.ok)
        assertTrue("truncated 报了：${cut.text}", cut.text.contains("\"truncated\":true"))
        assertTrue("totalChars 报了：${cut.text}", cut.text.contains("\"totalChars\":"))
    }

    @Test
    fun fetchRejectsNonHttpAndReportsErrors() {
        val r = ToolRegistry()
        WebTool.register(r, WebSearchClient(fetch = { _ -> "" }), fetcher = { _ -> throw java.io.IOException("HTTP 500") })
        val bad = r.execute("web_fetch", """{"url":"ftp://example.com/a"}""")
        assertTrue(bad.ok) // 工具执行成功，结果是「拒绝」的事实
        assertTrue("非 http 拒：${bad.text}", bad.text.contains("bad_url"))
        val err = r.execute("web_fetch", """{"url":"https://example.com/a"}""")
        assertTrue("失败报了：${err.text}", err.text.contains("fetch_error"))
        assertTrue("原因带账：${err.text}", err.text.contains("HTTP 500"))
    }

    @Test
    fun visibilityGateHidesFromSpecsAndBlocksExecute() {
        val r = ToolRegistry()
        var on = false
        WebTool.register(r, WebSearchClient(fetch = { _ -> ddgHtml }), fetcher = { ddgHtml }, visibleIf = { on })
        // 关着：清单里没有，调用被挡
        assertTrue("关着清单为空：${r.specs()}", r.specs().isEmpty())
        val blocked = r.execute("web_search", """{"query":"x"}""")
        assertFalse("关着不放行：${blocked.text}", blocked.ok)
        assertTrue("按不可用回话：${blocked.text}", blocked.text.contains("此刻不可用"))
        // 翻开：清单立刻回来
        on = true
        assertEquals(listOf("web_search", "web_fetch"), r.specs().map { it.name })
        val ok = r.execute("web_search", """{"query":"x"}""")
        assertTrue(ok.ok)
    }

    @Test
    fun entityDecodingCoversNamedAndNumeric() {
        assertEquals("a&b<c>d\"e'f — …", WebTool.htmlToReadableText("a&amp;b&lt;c&gt;d&quot;e&apos;f &mdash; &hellip;"))
        assertEquals("中文©2026", WebTool.htmlToReadableText("中文&#169;2026"))
        assertEquals("度°", WebTool.htmlToReadableText("度&#xB0;"))
    }
}
