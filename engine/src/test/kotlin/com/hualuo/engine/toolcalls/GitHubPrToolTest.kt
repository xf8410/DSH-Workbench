package com.hualuo.engine.toolcalls

import com.hualuo.engine.github.GitHubHttpResult
import com.hualuo.engine.github.GitHubPrClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 工具族对照表：闸门在不在，决定工具存不存在（默认拒执行）。
 * 网络层全注入（离线），闸门语义与拒绝路径走真逻辑。
 */
class GitHubPrToolTest {

    private var createCalls = 0
    private var mergeCalls = 0
    private var defaultBranchCalls = 0

    private fun client() = GitHubPrClient(
        postJson = { _, _, body ->
            createCalls++
            GitHubHttpResult(201, """{"number":57,"state":"open","draft":false,"head":{"ref":"workbench/x","sha":"abc123"},"base":{"ref":"main"},"html_url":"https://github.com/o/r/pull/57"}""", false).also { lastCreateBody = body }
        },
        putJson = { _, _, body ->
            mergeCalls++
            GitHubHttpResult(200, """{"merged":true,"sha":"dead10"}""", false).also { lastMergeBody = body }
        },
        fetch = { url, _, _, _ ->
            if (url.endsWith("/repos/o/r")) {
                defaultBranchCalls++
                GitHubHttpResult(200, """{"default_branch":"main"}""", false)
            } else if (url.endsWith("/pulls/57")) {
                GitHubHttpResult(200, """{"head":{"ref":"workbench/x","sha":"abc123def4567890abc123def4567890abc123de"}}""", false)
            } else GitHubHttpResult(404, """{"message":"nope"}""", false)
        },
    )

    private var lastCreateBody: String = ""
    private var lastMergeBody: String = ""

    private var seenProposal: GitHubPrProposal? = null

    @Test
    fun noGateMeansNoPrTools() {
        val registry = ToolRegistry()
        GitHubPrTool.register(registry, loadToken = { "tok" }, defaultRepo = { "o/r" }, prClient = client(), confirmer = null)
        assertEquals("不给闸门就一件都不注册：${registry.specs().map { it.name }}", 0, registry.size())
    }

    @Test
    fun createPrGoesThroughGateAndReportsVerbatim() {
        val registry = ToolRegistry()
        GitHubPrTool.register(
            registry, loadToken = { "tok" }, defaultRepo = { "o/r" }, prClient = client(),
            confirmer = PrConfirmer { p -> seenProposal = p; true },
        )
        val out = registry.execute(
            "github_create_pull_request",
            """{"repo":"o/r","head":"workbench/x","title":"第一刀","body":"正文","draft":false}""",
        )
        assertTrue("成功要带 PR 号与链接：${out.text}", out.ok && out.text.contains("#57") && out.text.contains("https://github.com/o/r/pull/57"))
        assertEquals("闸门看到的提议要完整：$seenProposal", "workbench/x", seenProposal?.head)
        assertEquals("main", seenProposal?.base)
        assertTrue("提议带标题：$seenProposal", seenProposal?.title?.contains("第一刀") == true)
        assertTrue("真发了 POST：", createCalls == 1)
        assertTrue("请求体带 base（默认分支真查了）：$lastCreateBody", lastCreateBody.contains("\"base\":\"main\""))
    }

    @Test
    fun rejectedGateMeansNothingCreated() {
        val registry = ToolRegistry()
        GitHubPrTool.register(
            registry, loadToken = { "tok" }, defaultRepo = { "o/r" }, prClient = client(),
            confirmer = PrConfirmer { false },
        )
        val out = registry.execute("github_create_pull_request", """{"repo":"o/r","head":"workbench/x","title":"t"}""")
        assertFalse("拒绝后不许有创建终态：${out.text}", out.text.contains("#57"))
        assertTrue("要说清没确认：${out.text}", out.text.contains("没有确认"))
        assertEquals("一个 POST 都不许发：", 0, createCalls)
    }

    @Test
    fun headEqualsBaseIsRejectedBeforeGate() {
        val registry = ToolRegistry()
        var gateSeen = false
        GitHubPrTool.register(
            registry, loadToken = { "tok" }, defaultRepo = { "o/r" }, prClient = client(),
            confirmer = PrConfirmer { gateSeen = true; true },
        )
        val out = registry.execute("github_create_pull_request", """{"repo":"o/r","head":"main","base":"main","title":"t"}""")
        assertTrue("同分支要拒：${out.text}", out.text.contains("同一条分支"))
        assertFalse("闸门都不用惊动：", gateSeen)
    }

    @Test
    fun badRefNameIsRejectedBeforeAnything() {
        val registry = ToolRegistry()
        GitHubPrTool.register(
            registry, loadToken = { "tok" }, defaultRepo = { "o/r" }, prClient = client(),
            confirmer = PrConfirmer { true },
        )
        val out = registry.execute("github_create_pull_request", """{"repo":"o/r","head":"bad..ref","title":"t"}""")
        assertTrue("坏 ref 要拒：${out.text}", out.text.contains("分支名不合法"))
    }

    @Test
    fun mergePrPinsShaIntoRequestBody() {
        val registry = ToolRegistry()
        GitHubPrTool.register(
            registry, loadToken = { "tok" }, defaultRepo = { "o/r" }, prClient = client(),
            confirmer = PrConfirmer { true },
        )
        val out = registry.execute(
            "github_merge_pull_request",
            """{"repo":"o/r","number":57,"expected_head_sha":"abc123def4567890abc123def4567890abc123de","method":"merge","commit_title":"合"}""",
        )
        assertTrue("合并要带 sha：${out.text}", out.ok && out.text.contains("dead10"))
        assertTrue("sha 钉进请求体：$lastMergeBody", lastMergeBody.contains("abc123def4567890abc123def4567890abc123de"))
        assertTrue("method 进请求体：$lastMergeBody", lastMergeBody.contains("\"merge_method\":\"merge\""))
    }

    @Test
    fun mergeWithBadShaFormatIsRejectedBeforeGate() {
        val registry = ToolRegistry()
        var gateSeen = false
        GitHubPrTool.register(
            registry, loadToken = { "tok" }, defaultRepo = { "o/r" }, prClient = client(),
            confirmer = PrConfirmer { gateSeen = true; true },
        )
        val out = registry.execute("github_merge_pull_request", """{"repo":"o/r","number":57,"expected_head_sha":"zzz"}""")
        assertTrue("坏 sha 要拒：${out.text}", out.text.contains("40 位") || out.text.contains("sha"))
        assertFalse("闸门不惊动：", gateSeen)
        assertEquals("没发 merge：", 0, mergeCalls)
    }

    @Test
    fun providerFailureCarriesVerbatimMessage() {
        val failClient = GitHubPrClient(
            postJson = { _, _, _ -> GitHubHttpResult(422, """{"message":"A pull request already exists for workbench/x."}""", false) },
            putJson = { _, _, _ -> GitHubHttpResult(200, "{}", false) },
            fetch = { _, _, _, _ -> GitHubHttpResult(200, """{"default_branch":"main"}""", false) },
        )
        val registry = ToolRegistry()
        GitHubPrTool.register(
            registry, loadToken = { "tok" }, defaultRepo = { "o/r" }, prClient = failClient,
            confirmer = PrConfirmer { true },
        )
        val out = registry.execute("github_create_pull_request", """{"repo":"o/r","head":"workbench/x","title":"t"}""")
        assertTrue("对方原话透传（零脱敏）：${out.text}", out.text.contains("A pull request already exists"))
        assertFalse(out.ok)
    }
}
