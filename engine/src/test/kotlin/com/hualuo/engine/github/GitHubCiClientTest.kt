package com.hualuo.engine.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub 只读客户端的账：解析只认真字段、坏条目数着报、失败绝不冒充成功、
 * 令牌只进请求头不进 URL 与错误、仓库写法不对就根本不碰网络。
 */
class GitHubCiClientTest {

    private class RecordingFetch(
        private val status: Int,
        private val body: String,
    ) : (String, String?) -> GitHubHttpResult {
        var calls = 0
        var lastUrl: String? = null
        var lastToken: String? = null

        override fun invoke(url: String, token: String?): GitHubHttpResult {
            calls += 1
            lastUrl = url
            lastToken = token
            return GitHubHttpResult(status, body)
        }
    }

    @Test
    fun runsParseCountsBadEntriesAndNeverFakesSuccess() {
        val body = """
            {"workflow_runs":[
              {"id":111,"name":"自检","head_sha":"abc123def4567890","status":"completed","conclusion":"success","created_at":"2026-09-16T01:00:00Z"},
              "这不是一条记录",
              {"id":222,"name":"自检","head_sha":"def456abc7890123","status":"in_progress","conclusion":null,"created_at":"2026-09-16T02:00:00Z"},
              {"id":"not-a-number","name":"x","head_sha":"sha","status":"completed"}
            ]}
        """.trimIndent()
        val fetch = RecordingFetch(200, body)
        val snapshot = GitHubCiClient(fetch).latestRuns("xf8410/hualuo-repo-tool", null, limit = 3)

        assertNull(snapshot.error)
        assertEquals("坏条目数着报（字符串一条 + id 不是数字一条）", 2, snapshot.badEntries)
        assertEquals("取到两条像样的", 2, snapshot.runs.size)
        assertEquals(111L, snapshot.runs[0].id)
        assertEquals("success", snapshot.runs[0].conclusion)
        assertEquals("还没跑完的 conclusion 是 null，不编一个", null, snapshot.runs[1].conclusion)
        assertEquals("in_progress", snapshot.runs[1].status)
    }

    @Test
    fun failuresSayTheStatusAndNeverPretendAList() {
        val snapshot = GitHubCiClient(RecordingFetch(403, "rate limited")).latestRuns("a/b", null)
        assertTrue("403 要指到令牌这条路：${snapshot.error}", snapshot.error!!.contains("403"))
        assertTrue("失败不拿半份名单冒充", snapshot.runs.isEmpty())

        val offline = GitHubCiClient(fetch = { _, _ -> GitHubHttpResult(0, "connect timed out") })
            .latestRuns("a/b", null)
        assertTrue("连不上也要说清：${offline.error}", offline.error!!.contains("连不上"))
    }

    @Test
    fun badRepoShapesAreRefusedBeforeTouchingNetwork() {
        val fetch = RecordingFetch(200, "{}")
        for (bad in listOf("只有一段", "a/b/c", "owner/../x", "owner/name?x=1", "owner/ name", "")) {
            val snapshot = GitHubCiClient(fetch).latestRuns(bad, null)
            assertTrue("「$bad」要被拒：${snapshot.error}", snapshot.error!!.contains("owner/name"))
        }
        assertEquals("写法不对就不许碰网络", 0, fetch.calls)
    }

    @Test
    fun githubLinksAreAcceptedAndTokenGoesOnlyIntoTheHeader() {
        val fetch = RecordingFetch(200, """{"workflow_runs":[]}""")
        val snapshot = GitHubCiClient(fetch)
            .latestRuns("https://github.com/xf8410/hualuo-repo-tool/", "sk-token-1234567890")

        assertNull(snapshot.error)
        assertEquals("整条链接粘进来也能用", "https://api.github.com/repos/xf8410/hualuo-repo-tool/actions/runs?per_page=3", fetch.lastUrl)
        assertEquals("令牌走请求头", "sk-token-1234567890", fetch.lastToken)
        assertTrue(
            "令牌不许混进 URL（它只住在头里）：${fetch.lastUrl}",
            fetch.lastUrl!!.contains("sk-token").not(),
        )
    }

    @Test
    fun releaseCheckDistinguishesNoneFromBroken() {
        val none = GitHubCiClient(RecordingFetch(404, """{"message":"Not Found"}"""))
            .latestRelease("a/b", null)
        assertTrue("404 = 还没发布过，不是网络坏", none.notFound)
        assertNull(none.error)

        val ok = GitHubCiClient(
            RecordingFetch(200, """{"tag_name":"v1.4.1","name":"发布","published_at":"2026-09-16T00:00:00Z"}"""),
        ).latestRelease("a/b", null)
        assertEquals("v1.4.1", ok.release?.tag)

        val broken = GitHubCiClient(RecordingFetch(500, "boom")).latestRelease("a/b", null)
        assertTrue(broken.error!!.contains("500"))
        assertFalse(broken.notFound)
    }

    @Test
    fun releaseBadRepoRefusedWithoutNetwork() {
        val fetch = RecordingFetch(200, "{}")
        val result = GitHubCiClient(fetch).latestRelease("not-a-repo", null)
        assertTrue(result.error!!.contains("owner/name"))
        assertEquals(0, fetch.calls)
    }

    @Test
    fun releasesListParsesTagsInOrder() {
        val body = """[{"tag_name":"v1.4.1","name":"发布","published_at":"2026-09-16T00:00:00Z"},
                       {"tag_name":"v1.4.0"},
                       {"bad_entry":true}]"""
        val fetch = RecordingFetch(200, body)
        val result = GitHubCiClient(fetch).releases("a/b", null)
        assertEquals(2, result.releases.size)
        assertEquals("v1.4.1", result.releases[0].tag)
        assertEquals("v1.4.0", result.releases[1].tag)
        assertEquals(1, result.badEntries)
        assertNull(result.error)
    }

    @Test
    fun releasesListErrorBridged() {
        val result = GitHubCiClient(RecordingFetch(500, "boom")).releases("a/b", null)
        assertTrue(result.error!!.contains("500"))
        assertTrue(result.releases.isEmpty())
    }

    @Test
    fun dispatchPostsPayloadAndCelebrates204() {
        var lastUrl: String? = null
        var lastBody: String? = null
        var calls = 0
        val post = { url: String, token: String?, body: String ->
            calls += 1; lastUrl = url; lastBody = body
            GitHubHttpResult(204, "", false)
        }
        val client = GitHubCiClient(RecordingFetch(200, "{}"), post)
        val result = client.dispatch("a/b", "ci.yml", "main", "tok", inputs = mapOf("note" to "回归"))
        assertTrue(result.dispatched)
        assertEquals(204, result.httpStatus)
        assertEquals(1, calls)
        assertTrue(lastUrl!!.endsWith("/repos/a/b/actions/workflows/ci.yml/dispatches"))
        assertTrue(lastBody!!.contains("\"ref\":\"main\""))
        assertTrue(lastBody!!.contains("\"note\":\"回归\""))
    }

    @Test
    fun dispatchRefusesEmptyWorkflowAndRefWithoutNetwork() {
        var calls = 0
        val post = { _: String, _: String?, _: String -> calls += 1; GitHubHttpResult(204, "", false) }
        val client = GitHubCiClient(RecordingFetch(200, "{}"), post)
        assertTrue(client.dispatch("a/b", " ", "main", null).error != null)
        assertTrue(client.dispatch("a/b", "ci.yml", " ", null).error != null)
        assertEquals(0, calls)
    }

    @Test
    fun dispatchExplainsKnownFailures() {
        val client404 = GitHubCiClient(RecordingFetch(200, "{}")) { _, _, _ -> GitHubHttpResult(404, "", false) }
        assertTrue(client404.dispatch("a/b", "ci.yml", "main", null).error!!.contains("workflow_dispatch"))
        val client403 = GitHubCiClient(RecordingFetch(200, "{}")) { _, _, _ -> GitHubHttpResult(403, "", false) }
        assertTrue(client403.dispatch("a/b", "ci.yml", "main", null).error!!.contains("actions:write"))
        val client422 = GitHubCiClient(RecordingFetch(200, "{}")) { _, _, _ -> GitHubHttpResult(422, "", false) }
        assertTrue(client422.dispatch("a/b", "ci.yml", "main", null).error!!.contains("422"))
    }
}
