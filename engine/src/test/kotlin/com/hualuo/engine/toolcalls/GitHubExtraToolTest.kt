package com.hualuo.engine.toolcalls

import com.hualuo.engine.github.GitHubActionsClient
import com.hualuo.engine.github.GitHubHttpResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 扩展工具族的契约：写类**给闸门才存在**、闸门拒绝时一次网络都不发、
 * 读类只注册读类、危险路径在碰网络之前就被挡住。
 */
class GitHubExtraToolTest {

    private fun readRegistry(): ToolRegistry = ToolRegistry().also {
        GitHubExtraReadTool.register(it, { "tok" }, { "o/r" })
    }

    private fun writeRegistry(confirmer: ActionConfirmer?, actions: GitHubActionsClient = GitHubActionsClient()): ToolRegistry =
        ToolRegistry().also {
            GitHubExtraWriteTool.register(it, { "tok" }, { "o/r" }, confirmer, actionsClient = actions)
        }

    @Test
    fun readFamilyRegistersTheNineReadTools() {
        val names = readRegistry().specs().map { it.name }.toSet()
        assertEquals(
            setOf(
                "github_pr_list",
                "github_pr_read",
                "github_pr_files",
                "github_commit_diff",
                "github_issue_list",
                "github_issue_read",
                "github_release_list",
                "github_artifact_list",
                "github_workflow_list",
            ),
            names,
        )
    }

    @Test
    fun writeFamilyDoesNotExistWithoutGate() {
        val registry = writeRegistry(confirmer = null)
        assertEquals("不给闸门就一个都不注册（默认拒执行）", 0, registry.size())
    }

    @Test
    fun writeFamilyRegistersElevenActionsWithGate() {
        val names = writeRegistry(ActionConfirmer { true }).specs().map { it.name }.toSet()
        assertEquals(
            setOf(
                "github_ci_rerun",
                "github_ci_cancel",
                "github_ci_dispatch",
                "github_artifact_download",
                "github_issue_create",
                "github_issue_comment",
                "github_issue_close",
                "github_release_create",
                "github_release_upload_asset",
                "github_edit_file",
                "github_pr_comment",
            ),
            names,
        )
    }

    @Test
    fun gateRejectionMeansNoNetworkAtAll() {
        var calls = 0
        val actions = GitHubActionsClient(postJson = { _, _, _ ->
            calls += 1
            GitHubHttpResult(201, "{}")
        })
        val registry = writeRegistry(ActionConfirmer { false }, actions)

        val outcome = registry.execute("github_ci_rerun", """{"repo":"o/r","run_id":42}""")

        assertFalse(outcome.ok)
        assertTrue("拒绝要说明「什么都没做」", outcome.text.contains("没有确认"))
        assertEquals("闸门拒了就不许发网", 0, calls)
    }

    @Test
    fun approvedRerunActuallyCallsClient() {
        var calls = 0
        var lastUrl = ""
        val actions = GitHubActionsClient(postJson = { url, _, _ ->
            calls += 1
            lastUrl = url
            GitHubHttpResult(201, "{}")
        })
        val registry = writeRegistry(ActionConfirmer { true }, actions)

        val outcome = registry.execute("github_ci_rerun", """{"repo":"o/r","run_id":42,"failed_only":true}""")

        assertTrue(outcome.text, outcome.ok)
        assertEquals(1, calls)
        assertTrue("failed_only 要走 rerun-failed-jobs", lastUrl.endsWith("/repos/o/r/actions/runs/42/rerun-failed-jobs"))
    }

    @Test
    fun artifactDownloadRefusesDotDotPathBeforeNetwork() {
        var calls = 0
        val actions = GitHubActionsClient(resolveRedirect = { _, _ -> calls += 1; null })
        val registry = writeRegistry(ActionConfirmer { true }, actions)

        val outcome = registry.execute(
            "github_artifact_download",
            """{"repo":"o/r","artifact_id":5,"path":"/sdcard/../data/data/x.zip"}""",
        )

        assertFalse(outcome.ok)
        assertTrue(outcome.text.contains(".."))
        assertEquals(0, calls)
    }

    @Test
    fun artifactDownloadRefusesNonZipTarget() {
        val registry = writeRegistry(ActionConfirmer { true }, GitHubActionsClient())
        val outcome = registry.execute(
            "github_artifact_download",
            """{"repo":"o/r","artifact_id":5,"path":"/sdcard/Download/build.apk"}""",
        )
        assertFalse(outcome.ok)
        assertTrue(outcome.text.contains(".zip"))
    }

    @Test
    fun missingRunIdIsNamedInTheFailure() {
        val registry = writeRegistry(ActionConfirmer { true }, GitHubActionsClient())
        val outcome = registry.execute("github_ci_cancel", """{"repo":"o/r"}""")
        assertFalse(outcome.ok)
        assertTrue(outcome.text.contains("run_id"))
    }

    @Test
    fun readToolReportsPullRequestsFilteredOutOfIssueList() {
        val registry = ToolRegistry().also {
            GitHubExtraReadTool.register(
                it,
                { "tok" },
                { "o/r" },
                issueClient = com.hualuo.engine.github.GitHubIssueClient(
                    fetch = { _, _, _, _ ->
                        GitHubHttpResult(
                            200,
                            """[{"number":1,"title":"issue","state":"open"},
                                {"number":2,"title":"pr","state":"open","pull_request":{"url":"x"}}]""",
                        )
                    },
                ),
            )
        }
        val outcome = registry.execute("github_issue_list", """{"repo":"o/r"}""")
        assertTrue(outcome.text, outcome.ok)
        assertTrue("筛掉 PR 要报数", outcome.text.contains("PR"))
    }
}
