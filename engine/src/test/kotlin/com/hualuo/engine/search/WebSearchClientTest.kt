package com.hualuo.engine.search

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索引擎件的契约：解析是防御式的（跳转链接解码、去标签解实体、认不出整条跳过）；
 * 「零结果」「空页」「连不上」都是说得清的失败，绝不拿空列表冒充。全程不碰真网。
 */
class WebSearchClientTest {

    private fun ddgPage(vararg entries: Pair<String, String>): String {
        val sb = StringBuilder("<html><body>")
        entries.forEach { (href, title) ->
            sb.append("<div class=\"result\">")
            sb.append("<a rel=\"nofollow\" class=\"result__a\" href=\"").append(href).append("\">")
            sb.append(title).append("</a>")
            sb.append("<a class=\"result__snippet\" href=\"#\">snippet of ").append(title).append("</a>")
            sb.append("</div>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    @Test
    fun extractsAndDecodesJumpWrappedResults() {
        val page = "<html><body><div class=\"result\">"
            .plus("<a rel=\"nofollow\" class=\"result__a\" href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa&rut=x\">Example &amp; Title</a>")
            .plus("<a class=\"result__snippet\" href=\"#\">First <b>snippet</b> &lt;here&gt;</a>")
            .plus("</div></body></html>")
        val outcome = WebSearchClient(fetch = { page }).search("测试词")
        assertTrue(outcome is SearchOutcome.Ok)
        val ok = outcome as SearchOutcome.Ok
        assertEquals(1, ok.results.size)
        assertEquals("https://example.com/a", ok.results[0].url)
        assertEquals("Example & Title", ok.results[0].title)
        assertEquals("First snippet <here>", ok.results[0].snippet)
        assertEquals("测试词", ok.query)
    }

    @Test
    fun directHttpsHrefsAreAcceptedAsIs() {
        val outcome = WebSearchClient(fetch = { ddgPage("https://direct.example.org/b" to "Direct Link") }).search("q")
        assertTrue(outcome is SearchOutcome.Ok)
        assertEquals("https://direct.example.org/b", (outcome as SearchOutcome.Ok).results[0].url)
    }

    @Test
    fun unrecognizableLinksAreSkippedNotPadded() {
        val page = ddgPage("/relative/nope" to "Bad One")
            .plus(ddgPage("https://good.example.org/yes" to "Good One"))
        val outcome = WebSearchClient(fetch = { page }).search("q")
        assertTrue(outcome is SearchOutcome.Ok)
        val results = (outcome as SearchOutcome.Ok).results
        assertEquals(1, results.size)
        assertEquals("Good One", results[0].title)
    }

    @Test
    fun resultCountIsCapped() {
        val page = ddgPage(
            "https://a.example/1" to "One",
            "https://a.example/2" to "Two",
            "https://a.example/3" to "Three",
        )
        val outcome = WebSearchClient(fetch = { page }, maxResults = 2).search("q")
        assertEquals(2, (outcome as SearchOutcome.Ok).results.size)
    }

    @Test
    fun pageWithNoResultMarkersFailsHonestly() {
        val outcome = WebSearchClient(fetch = { "<html>限流提示，没有结果结构</html>" }).search("q")
        assertTrue(outcome is SearchOutcome.Failed)
        assertTrue((outcome as SearchOutcome.Failed).reason.contains("没有搜到结果"))
    }

    @Test
    fun blankPageFailsAsRateLimitHint() {
        val outcome = WebSearchClient(fetch = { "   " }).search("q")
        assertTrue(outcome is SearchOutcome.Failed)
        assertTrue((outcome as SearchOutcome.Failed).reason.contains("空页"))
    }

    @Test
    fun blankQueryFailsFastWithoutTouchingFetcher() {
        var touched = false
        val outcome = WebSearchClient(fetch = { touched = true; "" }).search("   ")
        assertTrue(outcome is SearchOutcome.Failed)
        assertFalse(touched)
    }

    @Test
    fun fetcherFailureSurfacesWithTheStatusEvidence() {
        val outcome = WebSearchClient(fetch = { throw IOException("HTTP 503") }).search("q")
        assertTrue(outcome is SearchOutcome.Failed)
        val reason = (outcome as SearchOutcome.Failed).reason
        assertTrue(reason.contains("连不上"))
        assertTrue(reason.contains("HTTP 503"))
    }

    private fun assertFalse(b: Boolean) {
        org.junit.Assert.assertFalse(b)
    }
}
