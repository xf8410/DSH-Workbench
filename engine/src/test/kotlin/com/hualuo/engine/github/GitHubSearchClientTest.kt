package com.hualuo.engine.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库内代码搜索客户端的纯 JVM 契约测试：离线闸（无令牌、空词、坏仓写法都不碰网）、
 * 命中解析与坏条目计数、查询编码（空格吃成 %20 不是加号）、403 归因（配额不是没权限）、
 * 422 归因（搜索词写法）、incomplete 标记原样带上。fetch 注入，绝不碰真网。
 */
class GitHubSearchClientTest {

    private class FakeFetch : (String, String?, String?, Int) -> GitHubHttpResult {
        val urls = ArrayList<String>()
        val tokens = ArrayList<String?>()
        var next = GitHubHttpResult(200, "{}")

        override fun invoke(url: String, token: String?, accept: String?, maxChars: Int): GitHubHttpResult {
            urls += url
            tokens += token
            return next
        }
    }

    @Test
    fun searchWithoutTokenStaysOffline() {
        val fetch = FakeFetch()
        val result = GitHubSearchClient(fetch).searchCode("o/r", "LRow", null)

        assertTrue(result.hits.isEmpty())
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("令牌"))
        assertEquals(0, fetch.urls.size)
    }

    @Test
    fun searchWithBlankKeywordStaysOffline() {
        val fetch = FakeFetch()
        val result = GitHubSearchClient(fetch).searchCode("o/r", "   ", "tok")

        assertTrue(result.hits.isEmpty())
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("搜索词"))
        assertEquals(0, fetch.urls.size)
    }

    @Test
    fun searchWithBadRepoShapeStaysOffline() {
        val fetch = FakeFetch()
        val result = GitHubSearchClient(fetch).searchCode("just-a-name", "x", "tok")

        assertTrue(result.hits.isEmpty())
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("owner/name"))
        assertEquals(0, fetch.urls.size)
    }

    @Test
    fun searchParsesHitsTotalAndCountsBadEntries() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(
            200,
            "{\"total_count\":3,\"incomplete_results\":false,\"items\":[" +
                "{\"name\":\"Composer.kt\",\"path\":\"app/src/main/kotlin/Composer.kt\"}," +
                "{\"name\":\"Drawer.kt\",\"path\":\"app/src/main/kotlin/Drawer.kt\"}," +
                "{\"broken\":1}]}",
        )
        val result = GitHubSearchClient(fetch).searchCode("xf8410/hualuo-repo-tool", "LRow", "tok")

        assertNull(result.error)
        assertEquals(3, result.totalCount)
        assertFalse(result.incomplete)
        assertEquals(1, result.badEntries)
        assertEquals(2, result.hits.size)
        assertEquals("Composer.kt", result.hits.first().name)
        assertEquals("app/src/main/kotlin/Composer.kt", result.hits.first().path)
        assertEquals("tok", fetch.tokens.first())
        val url = fetch.urls.first()
        assertTrue(url.contains("/search/code?q="))
        assertTrue(url.contains("repo%3Axf8410%2Fhualuo-repo-tool"))
        assertTrue(url.contains("per_page="))
        assertFalse(url.contains("+"))
    }

    @Test
    fun searchEncodesSpacesAsPercent20NotPlus() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(200, "{\"total_count\":0,\"items\":[]}")
        GitHubSearchClient(fetch).searchCode("o/r", "a b", "tok")

        val url = fetch.urls.first()
        assertTrue(url.contains("a%20b"))
        assertFalse(url.contains("a+b"))
    }

    @Test
    fun search403SaysQuotaNotPermission() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(403, "rate limited")
        val result = GitHubSearchClient(fetch).searchCode("o/r", "x", "tok")

        assertTrue(result.hits.isEmpty())
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("403"))
        assertTrue(result.error!!.contains("配额"))
    }

    @Test
    fun search422SaysBadQuery() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(422, "invalid")
        val result = GitHubSearchClient(fetch).searchCode("o/r", "x", "tok")

        assertNotNull(result.error)
        assertTrue(result.error!!.contains("422"))
        assertTrue(result.error!!.contains("搜索词"))
    }

    @Test
    fun searchCarriesIncompleteFlag() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(
            200,
            "{\"total_count\":100,\"incomplete_results\":true,\"items\":[" +
                "{\"name\":\"a.kt\",\"path\":\"a.kt\"}]}",
        )
        val result = GitHubSearchClient(fetch).searchCode("o/r", "x", "tok")

        assertTrue(result.incomplete)
        assertEquals(1, result.hits.size)
        assertNull(result.error)
    }
}
