package com.hualuo.engine.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 客户端对照表（离线：POST/PUT/GET 全注入，不碰网络）。
 * 每组输入打印期望与实际（契约二.8）。
 */
class GitHubPrClientTest {

    private class FakeTransport(
        var onCreate: (String, String?) -> GitHubHttpResult = { _, _ -> GitHubHttpResult(0, "not stubbed", false) },
        var onMerge: (String, String?) -> GitHubHttpResult = { _, _ -> GitHubHttpResult(0, "not stubbed", false) },
        var onFetch: (String, String?) -> GitHubHttpResult = { _, _ -> GitHubHttpResult(0, "not stubbed", false) },
    ) {
        val createBodies = mutableListOf<String>()
        val mergeBodies = mutableListOf<String>()
        val createUrls = mutableListOf<String>()
        val mergeUrls = mutableListOf<String>()
    }

    private fun client(t: FakeTransport) = GitHubPrClient(
        postJson = { url, tok, body ->
            t.createUrls.add(url); t.createBodies.add(body); t.onCreate(url, tok)
        },
        putJson = { url, tok, body ->
            t.mergeUrls.add(url); t.mergeBodies.add(body); t.onMerge(url, tok)
        },
        fetch = { url, tok, _, _ -> t.onFetch(url, tok) },
    )

    private val createdBody = """
        {"number":57,"state":"open","draft":false,
         "head":{"ref":"workbench/pr-tools","sha":"abc123def456"},
         "base":{"ref":"main"},
         "html_url":"https://github.com/xf8410/hualuo-repo-tool/pull/57"}
    """.trimIndent()

    @Test
    fun createPullParsesKeyFieldsAndPostsRightUrl() {
        val t = FakeTransport(onCreate = { _, _ -> GitHubHttpResult(201, createdBody, false) })
        val pr = client(t).createPullRequest(
            repo = "xf8410/hualuo-repo-tool",
            head = "workbench/pr-tools", base = "main",
            title = "M4 第一刀", body = "正文", draft = false, token = "tok",
        )
        assertEquals("URL 要打 /pulls：${t.createUrls}", 1, t.createUrls.size)
        assertTrue("URL 形状：${t.createUrls[0]}", t.createUrls[0].endsWith("/repos/xf8410/hualuo-repo-tool/pulls"))
        assertEquals(57L, pr.number)
        assertEquals("open", pr.state)
        assertEquals("workbench/pr-tools", pr.headRef)
        assertEquals("abc123def456", pr.headSha)
        assertEquals("main", pr.baseRef)
        assertEquals("https://github.com/xf8410/hualuo-repo-tool/pull/57", pr.htmlUrl)
        assertTrue("请求体带 title：${t.createBodies[0]}", t.createBodies[0].contains("\"title\":\"M4 第一刀\""))
        assertTrue("请求体带 head/base：${t.createBodies[0]}", t.createBodies[0].contains("\"head\":\"workbench/pr-tools\"") && t.createBodies[0].contains("\"base\":\"main\""))
    }

    @Test
    fun createPullFailureCarriesProviderVerbatim() {
        val t = FakeTransport(onCreate = { _, _ -> GitHubHttpResult(422, """{"message":"A pull request already exists for workbench/pr-tools."}""", false) })
        val err = runCatching {
            client(t).createPullRequest("xf8410/hualuo-repo-tool", "workbench/pr-tools", "main", "t", "b", false, "tok")
        }.exceptionOrNull()
        assertTrue("422 要抛：$err", err is PrRequestFailed)
        assertTrue("对方原话透传（零脱敏）：${(err as PrRequestFailed).detail}", err.detail.contains("A pull request already exists"))
    }

    @Test
    fun mergePullIsShaPinned() {
        val t = FakeTransport(onMerge = { _, _ -> GitHubHttpResult(200, """{"merged":true,"sha":"dead10beef"}""", false) })
        val sha = client(t).mergePullRequest(
            repo = "xf8410/hualuo-repo-tool", number = 57,
            expectedHeadSha = "abc123def456", method = "merge",
            commitTitle = "合并 PR #57", token = "tok",
        )
        assertEquals("dead10beef", sha.sha)
        assertTrue("merged=true：$sha", sha.merged)
        assertTrue("merge URL：${t.mergeUrls[0]}", t.mergeUrls[0].endsWith("/repos/xf8410/hualuo-repo-tool/pulls/57/merge"))
        assertTrue("sha 要钉进请求体（不合错版本）：${t.mergeBodies[0]}", t.mergeBodies[0].contains("\"sha\":\"abc123def456\""))
    }

    @Test
    fun mergePullFailsClosedOn405AlreadyMerged() {
        val t = FakeTransport(onMerge = { _, _ -> GitHubHttpResult(405, """{"message":"Pull Request is not mergeable"}""", false) })
        val err = runCatching {
            client(t).mergePullRequest("xf8410/hualuo-repo-tool", 57, "abc", "merge", "t", "tok")
        }.exceptionOrNull()
        assertTrue("merged!=true 一律失败：$err", err is PrRequestFailed)
        assertTrue("原话带出：${(err as PrRequestFailed).detail}", err.detail.contains("not mergeable"))
    }

    @Test
    fun pullHeadShaReadsHeadObject() {
        val t = FakeTransport(onFetch = { url, _ ->
            if (url.endsWith("/pulls/57")) GitHubHttpResult(200, """{"head":{"ref":"b1","sha":"feed"}}""", false)
            else GitHubHttpResult(404, """{"message":"Not Found"}""", false)
        })
        assertEquals("feed", client(t).pullHeadSha("o/r", 57, "tok"))
    }

    @Test
    fun repoDefaultBranchReadsField() {
        val t = FakeTransport(onFetch = { url, _ ->
            if (url.endsWith("/repos/o/r")) GitHubHttpResult(200, """{"default_branch":"main"}""", false)
            else GitHubHttpResult(0, "", false)
        })
        assertEquals("main", client(t).repoDefaultBranch("o/r", "tok"))
    }
}
