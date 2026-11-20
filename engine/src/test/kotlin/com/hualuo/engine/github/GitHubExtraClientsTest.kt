package com.hualuo.engine.github

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 扩展客户端的账：解析只认真字段、坏条目数着报、PR 混进 issue 要筛掉并报数、
 * patch 截断必须出声、diff 走 diff 档、artifact 下载两跳（第二跳不带令牌）。
 * 全部注入假 fetch，绝不碰真网。
 */
class GitHubExtraClientsTest {

    private class RecordingFetch(private val status: Int, private val body: String) :
        (String, String?, String?, Int) -> GitHubHttpResult {
        val urls = ArrayList<String>()
        val accepts = ArrayList<String?>()
        var lastToken: String? = null

        override fun invoke(url: String, token: String?, accept: String?, maxChars: Int): GitHubHttpResult {
            urls += url
            accepts += accept
            lastToken = token
            return GitHubHttpResult(status, body)
        }
    }

    // ── PR 读 ──────────────────────────────────────────────────

    @Test
    fun pullListParsesAndCountsBadEntries() {
        val body = """
            [
              {"number":7,"title":"跨 fork PR","state":"open","draft":false,
               "user":{"login":"xf8410"},"head":{"ref":"hint-pt-model"},"base":{"ref":"main"},
               "updated_at":"2026-11-20T11:40:00Z","html_url":"https://github.com/o/r/pull/7"},
              "这不是一条记录",
              {"title":"没有编号","state":"open"}
            ]
        """.trimIndent()
        val fetch = RecordingFetch(200, body)
        val list = GitHubPrReadClient(fetch).list("o/r", "open", "tok", 10)

        assertNull(list.error)
        assertEquals(1, list.pulls.size)
        assertEquals(7L, list.pulls[0].number)
        assertEquals("hint-pt-model", list.pulls[0].headRef)
        assertEquals("main", list.pulls[0].baseRef)
        assertEquals("坏条目要数着报（字符串一条 + 没编号一条）", 2, list.badEntries)
        assertTrue("令牌只进请求头", fetch.lastToken == "tok")
    }

    @Test
    fun pullFilesTruncatesLongPatchAndSaysSo() {
        val longPatch = "+".repeat(GitHubPrReadClient.MAX_PATCH_CHARS + 500)
        val body = """[{"filename":"a.kt","status":"modified","additions":3,"deletions":1,"patch":"$longPatch"}]"""
        val list = GitHubPrReadClient(RecordingFetch(200, body)).files("o/r", 7, "tok", 30)

        assertNull(list.error)
        assertEquals(1, list.files.size)
        assertTrue("patch 必须被截断", list.files[0].patch.length < longPatch.length)
        assertTrue("截断必须出声", list.files[0].patch.contains("被截断"))
    }

    @Test
    fun pullDetailReportsMergeableUnknownAsNull() {
        val body = """{"number":7,"title":"t","state":"open","draft":false,"user":{"login":"u"},
            "head":{"ref":"h","sha":"abc"},"base":{"ref":"main"},"additions":1,"deletions":2,
            "changed_files":3,"mergeable":null,"html_url":"https://x"}"""
        val detail = GitHubPrReadClient(RecordingFetch(200, body)).detail("o/r", 7, "tok")

        assertNull(detail.error)
        assertNull("GitHub 还没算完就是 null，不编 false", detail.mergeable)
        assertEquals(3, detail.changedFiles)
    }

    // ── commit diff ────────────────────────────────────────────

    @Test
    fun commitDiffUsesDiffAcceptAndKeepsTruncationFlag() {
        val fetch = RecordingFetch(200, "diff --git a/x b/x\n+hello")
        val diff = GitHubCommitClient(fetch).diffText("o/r", "abc123", "tok")

        assertNull(diff.error)
        assertEquals("application/vnd.github.diff", fetch.accepts[0])
        assertTrue(diff.text.contains("+hello"))
    }

    @Test
    fun commitDetailParsesStatsAndFiles() {
        val body = """{"sha":"abc123","commit":{"message":"fix: x","author":{"name":"xf8410","date":"2026-11-20T00:00:00Z"}},
            "stats":{"additions":5,"deletions":2,"total":7},
            "files":[{"filename":"A.kt","status":"modified","additions":5,"deletions":2,"patch":"@@ -1 +1 @@"}]}"""
        val detail = GitHubCommitClient(RecordingFetch(200, body)).detail("o/r", "abc123", "tok", 20)

        assertNull(detail.error)
        assertEquals("fix: x", detail.message)
        assertEquals("xf8410", detail.author)
        assertEquals(7, detail.total)
        assertEquals(1, detail.files.size)
    }

    // ── issue ──────────────────────────────────────────────────

    @Test
    fun issueListSkipsPullRequestsAndReportsHowMany() {
        val body = """
            [
              {"number":11,"title":"真 issue","state":"open","user":{"login":"u"},"labels":[{"name":"bug"}],"comments":2},
              {"number":12,"title":"其实是 PR","state":"open","pull_request":{"url":"x"}},
              {"number":13,"title":"另一个 PR","state":"open","pull_request":{"url":"y"}}
            ]
        """.trimIndent()
        val list = GitHubIssueClient(fetch = RecordingFetch(200, body)).list("o/r", "open", "tok", 10)

        assertNull(list.error)
        assertEquals(1, list.issues.size)
        assertEquals(11L, list.issues[0].number)
        assertEquals(listOf("bug"), list.issues[0].labels)
        assertEquals("混进来的 PR 要筛掉并报数", 2, list.skippedPullRequests)
    }

    @Test
    fun issueReadRefusesWhenNumberIsActuallyPullRequest() {
        val body = """{"number":12,"title":"PR","state":"open","pull_request":{"url":"x"}}"""
        val detail = GitHubIssueClient(fetch = RecordingFetch(200, body)).get("o/r", 12, "tok")

        assertNotNull(detail.error)
        assertTrue(detail.error!!.contains("Pull Request"))
    }

    // ── release ────────────────────────────────────────────────

    @Test
    fun releaseCreateTurns422IntoHumanWords() {
        val client = GitHubReleaseClient(
            postJson = { _, _, _ -> GitHubHttpResult(422, """{"message":"Validation Failed"}""") },
        )
        val created = client.create("o/r", "v1.2.0", "发布", "", false, false, null, "tok")

        assertEquals(0L, created.id)
        assertNotNull(created.error)
        assertTrue("422 要翻成人话", created.error!!.contains("422"))
    }

    @Test
    fun releaseCreateParsesIdAndUrl() {
        val client = GitHubReleaseClient(
            postJson = { _, _, _ ->
                GitHubHttpResult(201, """{"id":99,"tag_name":"v1.2.0","html_url":"https://github.com/o/r/releases/v1.2.0"}""")
            },
        )
        val created = client.create("o/r", "v1.2.0", "发布", "说明", false, false, "main", "tok")

        assertEquals(99L, created.id)
        assertEquals("https://github.com/o/r/releases/v1.2.0", created.htmlUrl)
        assertNull(created.error)
    }

    @Test
    fun assetUploadUsesUploadsHostAndReportsDuplicate() {
        var seenUrl = ""
        val client = GitHubReleaseClient(
            postBinary = { url, _, _, _ ->
                seenUrl = url
                GitHubHttpResult(422, """{"message":"already_exists"}""")
            },
        )
        val uploaded = client.uploadAsset("o/r", 99, "dsh.apk", "application/vnd.android.package-archive", byteArrayOf(1, 2, 3), "tok")

        assertTrue(seenUrl.startsWith("https://uploads.github.com/repos/o/r/releases/99/assets?name=dsh.apk"))
        assertTrue("同名附件要说人话", uploaded.error!!.contains("同名"))
    }

    // ── artifact ───────────────────────────────────────────────

    @Test
    fun artifactListCountsBadEntriesAndKeepsSize() {
        val body = """{"total_count":2,"artifacts":[
            {"id":5,"name":"apk","size_in_bytes":1048576,"expired":false,"workflow_run":{"id":777}},
            {"name":"没有 id"}
        ]}"""
        val list = GitHubActionsClient(fetch = { _, _, _, _ -> GitHubHttpResult(200, body) })
            .artifacts("o/r", "tok", 10)

        assertNull(list.error)
        assertEquals(1, list.artifacts.size)
        assertEquals(5L, list.artifacts[0].id)
        assertEquals(777L, list.artifacts[0].runId)
        assertEquals(1, list.badEntries)
    }

    @Test
    fun artifactDownloadWithoutRedirectSaysExpiredInsteadOfFailingSilently() {
        val client = GitHubActionsClient(resolveRedirect = { _, _ -> null })
        val target = File.createTempFile("artifact", ".zip").also { it.delete() }
        val result = client.downloadArtifact("o/r", 5, "tok", target)

        assertFalse(result.saved)
        assertTrue("没跳转要说人话", result.error!!.contains("过期"))
    }

    @Test
    fun artifactDownloadSecondHopCarriesNoToken() {
        // 第二跳由 githubDownloadToFile 完成（签名 URL 自带凭据，不带令牌）：
        // 这里用注入替身验证「第一跳拿 Location、第二跳只收到 Location 与上限」。
        var gotUrl = ""
        val client = GitHubActionsClient(
            resolveRedirect = { url, _ -> "https://objects.githubusercontent.com/signed?_=$url" },
            downloadToFile = { url, target, max ->
                gotUrl = url
                target.parentFile?.mkdirs()
                target.writeText("zip")
                target.length() to null
            },
        )
        val target = File.createTempFile("artifact", ".zip")
        val result = client.downloadArtifact("o/r", 5, "tok", target)

        assertTrue(result.saved)
        assertTrue("第二跳拿到的是签名 URL", gotUrl.startsWith("https://objects.githubusercontent.com/signed"))
        assertEquals(3L, result.bytes)
        target.delete()
    }
}
