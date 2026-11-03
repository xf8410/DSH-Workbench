package com.hualuo.engine.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库工作台客户端的纯 JVM 契约测试：清单解析、路径编码、读文件（JSON 档拿内容+sha、
 * 超限退 raw 档）、分支/提交历史、改码提交（sha 对账 + 409 冲突出声）、CI 深看三层
 * （runs / run 的 jobs / job 日志有界读）。fetch/putJson 全部注入，绝不碰真网。
 */
class GitHubRepoClientTest {

    private class FakeFetch : (String, String?, String?, Int) -> GitHubHttpResult {
        val urls = ArrayList<String>()
        val tokens = ArrayList<String?>()
        val accepts = ArrayList<String?>()
        val caps = ArrayList<Int>()
        var next = GitHubHttpResult(200, "[]")

        override fun invoke(url: String, token: String?, accept: String?, maxChars: Int): GitHubHttpResult {
            urls += url
            tokens += token
            accepts += accept
            caps += maxChars
            return next
        }
    }

    private class FakePut : (String, String?, String) -> GitHubHttpResult {
        val urls = ArrayList<String>()
        val bodies = ArrayList<String>()
        var next = GitHubHttpResult(200, "{}")

        override fun invoke(url: String, token: String?, body: String): GitHubHttpResult {
            urls += url
            bodies += body
            return next
        }
    }

    private fun client(fetch: FakeFetch, put: FakePut = FakePut()) = GitHubRepoClient(fetch, put)

    @Test
    fun listMyReposWithoutTokenDoesNotTouchNetwork() {
        val fetch = FakeFetch()
        val list = client(fetch).listMyRepos(null)

        assertTrue(list.repos.isEmpty())
        assertNotNull(list.error)
        assertTrue(list.error!!.contains("令牌"))
        assertEquals(0, fetch.urls.size)
    }

    @Test
    fun listMyReposParsesSummariesAndCountsBadEntries() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(
            200,
            "[{\"full_name\":\"xf8410/hualuo-repo-tool\",\"private\":true,\"description\":\"app\"," +
                "\"default_branch\":\"main\",\"updated_at\":\"2026-09-17T07:14:44Z\",\"size\":1234}," +
                "{\"full_name\":\"xf8410/umaai-rs\",\"private\":false,\"description\":null," +
                "\"default_branch\":\"master\",\"updated_at\":\"\",\"size\":0}," +
                "{\"no_name_here\":true}]",
        )
        val list = client(fetch).listMyRepos("tok")

        assertEquals(2, list.repos.size)
        assertEquals(1, list.badEntries)
        assertNull(list.error)
        val first = list.repos.first()
        assertEquals("xf8410/hualuo-repo-tool", first.fullName)
        assertTrue(first.isPrivate)
        assertEquals("main", first.defaultBranch)
        assertEquals(1234L, first.sizeKb)
        assertFalse(list.repos[1].isPrivate)
        assertEquals("master", list.repos[1].defaultBranch)
        assertTrue(fetch.urls.first().contains("/user/repos?per_page="))
        assertTrue(fetch.urls.first().contains("affiliation=owner"))
    }

    @Test
    fun listMyReposMaps403ToTokenAdvice() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(403, "forbidden")
        val list = client(fetch).listMyRepos("tok")

        assertTrue(list.repos.isEmpty())
        assertTrue(list.error!!.contains("403"))
        assertTrue(list.error!!.contains("令牌"))
    }

    @Test
    fun listUserReposRejectsBadOwnerBeforeFetching() {
        val fetch = FakeFetch()
        val list = client(fetch).listUserRepos("a/b", null)

        assertTrue(list.repos.isEmpty())
        assertNotNull(list.error)
        assertEquals(0, fetch.urls.size)
    }

    @Test
    fun listUserRepos404SaysNoSuchUser() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(404, "not found")
        val list = client(fetch).listUserRepos("ghost", null)

        assertTrue(list.error!!.contains("404"))
        assertTrue(list.error!!.contains("用户"))
    }

    @Test
    fun browseSortsDirsFirstAndCountsBadEntries() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(
            200,
            "[{\"name\":\"z.kt\",\"path\":\"z.kt\",\"type\":\"file\",\"size\":10}," +
                "{\"name\":\"docs\",\"path\":\"docs\",\"type\":\"dir\",\"size\":0}," +
                "{\"weird\":1}]",
        )
        val browse = client(fetch).browse("xf8410/hualuo-repo-tool", "", null, "tok")

        assertNull(browse.error)
        assertEquals(1, browse.badEntries)
        assertEquals(2, browse.entries.size)
        assertTrue(browse.entries.first().isDir)
        assertEquals("docs", browse.entries.first().name)
        assertEquals("z.kt", browse.entries.last().name)
        assertTrue(fetch.urls.first().endsWith("/repos/xf8410/hualuo-repo-tool/contents"))
    }

    @Test
    fun browseEncodesEachPathSegmentKeepingSlashes() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(200, "[]")
        client(fetch).browse("o/r", "src main/com/demo", null, null)

        val url = fetch.urls.first()
        assertTrue(url.contains("/contents/src%20main/com/demo"))
        assertFalse(url.contains("+"))
    }

    @Test
    fun browseBadRepoShapeStaysOffline() {
        val fetch = FakeFetch()
        val browse = client(fetch).browse("just-a-name", "", null, null)

        assertTrue(browse.error!!.contains("owner/name"))
        assertEquals(0, fetch.urls.size)
    }

    @Test
    fun browse404SaysDirectoryOrBranchWrong() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(404, "nope")
        val browse = client(fetch).browse("o/r", "no/such/dir", null, null)

        assertTrue(browse.error!!.contains("404"))
    }

    @Test
    fun readFileDecodesBase64AndCarriesSha() {
        val fetch = FakeFetch()
        // base64("hello repo") = aGVsbG8gcmVwbw==
        fetch.next = GitHubHttpResult(
            200,
            "{\"name\":\"README.md\",\"path\":\"README.md\",\"sha\":\"abc123\"," +
                "\"size\":10,\"encoding\":\"base64\",\"content\":\"aGVsbG8gcmVwbw==\"}",
        )
        val file = client(fetch).readFile("o/r", "README.md", null, "tok")

        assertNull(file.error)
        assertEquals("hello repo", file.text)
        assertEquals(10, file.charCount)
        assertFalse(file.truncated)
        assertEquals("abc123", file.sha)
        assertFalse(file.tooBig)
        assertTrue(fetch.caps.first() > GITHUB_MAX_BODY_CHARS)
    }

    @Test
    fun readFileTooBigFallsBackToRawPreviewAndKeepsSha() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(
            200,
            "{\"name\":\"big.log\",\"path\":\"big.log\",\"sha\":\"bigsha\"," +
                "\"size\":2000000,\"encoding\":\"none\",\"content\":\"\"}",
        )
        val rawPreview = GitHubHttpResult(200, "head of a big file", truncated = true)

        val file = GitHubRepoClient({ url, token, accept, maxChars ->
            fetch.urls += url
            fetch.accepts += accept
            fetch.caps += maxChars
            if (accept == null) fetch.next else rawPreview
        }).readFile("o/r", "big.log", null, "tok")

        assertEquals("head of a big file", file.text)
        assertTrue(file.truncated)
        assertEquals("bigsha", file.sha)
        assertTrue(file.tooBig)
        assertEquals(GITHUB_ACCEPT_RAW, fetch.accepts.last())
    }

    @Test
    fun readFileSendsRefQueryWhenGiven() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(
            200,
            "{\"name\":\"a b.kt\",\"path\":\"a b.kt\",\"sha\":\"s\",\"size\":1,\"encoding\":\"base64\",\"content\":\"eA==\"}",
        )
        client(fetch).readFile("o/r", "a b.kt", "dev branch", null)

        val url = fetch.urls.first()
        assertTrue(url.contains("/contents/a%20b.kt?ref=dev%20branch"))
    }

    @Test
    fun readFileUndecodableContentSaysSoInsteadOfGarbling() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(
            200,
            "{\"name\":\"x\",\"path\":\"x\",\"sha\":\"s\",\"size\":3,\"encoding\":\"base64\",\"content\":\"!!!not-base64!!!\"}",
        )
        val file = client(fetch).readFile("o/r", "x", null, null)

        assertNull(file.text)
        assertTrue(file.error!!.contains("解不出来"))
    }

    @Test
    fun readFile404SaysFileOrBranchWrong() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(404, "nope")
        val file = client(fetch).readFile("o/r", "gone.kt", null, null)

        assertNull(file.text)
        assertTrue(file.error!!.contains("404"))
    }

    @Test
    fun listBranchesParsesNamesAndShasSortedByName() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(
            200,
            "[{\"name\":\"workbench/courier\",\"commit\":{\"sha\":\"sha-c\"}}," +
                "{\"name\":\"main\",\"commit\":{\"sha\":\"sha-m\"}}," +
                "{\"nameless\":1}]",
        )
        val list = client(fetch).listBranches("o/r", "tok")

        assertNull(list.error)
        assertEquals(1, list.badEntries)
        assertEquals(2, list.branches.size)
        assertEquals("main", list.branches.first().name)
        assertEquals("sha-m", list.branches.first().commitSha)
        assertEquals("workbench/courier", list.branches.last().name)
        assertTrue(fetch.urls.first().contains("/repos/o/r/branches?per_page="))
    }

    @Test
    fun listBranchesBadRepoShapeStaysOffline() {
        val fetch = FakeFetch()
        val list = client(fetch).listBranches("bad", null)

        assertTrue(list.error!!.contains("owner/name"))
        assertEquals(0, fetch.urls.size)
    }

    @Test
    fun listCommitsParsesFirstLineAuthorDateAndSendsParams() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(
            200,
            "[{\"sha\":\"aaa111\",\"commit\":{\"message\":\"修红：箭头改 ASCII\\n\\n正文不该出现\"," +
                "\"author\":{\"name\":\"xf8410\",\"date\":\"2026-09-17T14:39:21Z\"}}}," +
                "{\"sha\":\"bbb222\",\"commit\":{\"message\":\"第二刀\",\"author\":{\"name\":\"某人\",\"date\":\"\"}}}," +
                "{\"broken\":1}]",
        )
        val list = client(fetch).listCommits("o/r", "dev branch", "app/src/main/kotlin", "tok")

        assertNull(list.error)
        assertEquals(1, list.badEntries)
        assertEquals(2, list.commits.size)
        assertEquals("aaa111", list.commits.first().sha)
        assertEquals("修红：箭头改 ASCII", list.commits.first().messageFirstLine)
        assertEquals("xf8410", list.commits.first().author)
        val url = fetch.urls.first()
        assertTrue(url.contains("/repos/o/r/commits?"))
        assertTrue(url.contains("sha=dev%20branch"))
        assertTrue(url.contains("path=app/src/main/kotlin"))
    }

    @Test
    fun updateFilePutsBase64BodyWithBranchAndSha() {
        val fetch = FakeFetch()
        val put = FakePut()
        put.next = GitHubHttpResult(
            200,
            "{\"commit\":{\"sha\":\"commit-9\"},\"content\":{\"sha\":\"content-8\"}}",
        )
        val written = client(fetch, put).updateFile("o/r", "src/a b.kt", "dev", "new body", "改一行", "old-sha", "tok")

        assertNull(written.error)
        assertEquals("commit-9", written.commitSha)
        assertEquals("content-8", written.contentSha)
        assertEquals("/repos/o/r/contents/src/a%20b.kt", put.urls.first().substringAfter("api.github.com"))
        val body = put.bodies.first()
        assertTrue(body.contains("\"message\":\"改一行\""))
        assertTrue(body.contains("\"branch\":\"dev\""))
        assertTrue(body.contains("\"sha\":\"old-sha\""))
        // base64("new body") = bmV3IGJvZHk=
        assertTrue(body.contains("bmV3IGJvZHk="))
        assertEquals(0, fetch.urls.size)
    }

    @Test
    fun updateFileWithoutShaOmitsTheField() {
        val put = FakePut()
        put.next = GitHubHttpResult(200, "{\"commit\":{\"sha\":\"c\"},\"content\":{\"sha\":\"s\"}}")
        client(FakeFetch(), put).updateFile("o/r", "a.kt", null, "x", "新建内容", null, "tok")

        assertFalse(put.bodies.first().contains("sha"))
        assertTrue(put.bodies.first().contains("\"branch\":\"main\""))
    }

    @Test
    fun updateFileBlankMessageIsRefusedOffline() {
        val put = FakePut()
        val written = client(FakeFetch(), put).updateFile("o/r", "a.kt", null, "x", "   ", "s", "tok")

        assertNull(written.commitSha)
        assertTrue(written.error!!.contains("message"))
        assertEquals(0, put.urls.size)
    }

    @Test
    fun updateFileWithoutTokenIsRefusedOffline() {
        val put = FakePut()
        val written = client(FakeFetch(), put).updateFile("o/r", "a.kt", null, "x", "msg", "s", null)

        assertTrue(written.error!!.contains("令牌"))
        assertEquals(0, put.urls.size)
    }

    @Test
    fun updateFileConflictSaysReopenNotOverwrite() {
        val put = FakePut()
        put.next = GitHubHttpResult(409, "{\"message\":\"conflict\"}")
        val written = client(FakeFetch(), put).updateFile("o/r", "a.kt", null, "x", "msg", "old", "tok")

        assertNull(written.commitSha)
        assertTrue(written.error!!.contains("被别人改过"))
        assertTrue(written.error!!.contains("409"))
    }

    @Test
    fun updateFileBinaryContentIsRefusedOffline() {
        val put = FakePut()
        val written = client(FakeFetch(), put).updateFile("o/r", "a.bin", null, "x\u0000y", "msg", "s", "tok")

        assertTrue(written.error!!.contains("文本"))
        assertEquals(0, put.urls.size)
    }

    // ── CI 深看三层 ────────────────────────────────────────────────────────

    @Test
    fun runsParsesWorkflowListForAnyRepo() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(
            200,
            "{\"workflow_runs\":[{\"id\":77,\"name\":\"自检\",\"head_sha\":\"abc1234def\"," +
                "\"status\":\"completed\",\"conclusion\":\"success\",\"created_at\":\"2026-09-17T14:00:00Z\"}," +
                "{\"broken\":1}]}",
        )
        val snapshot = client(fetch).runs("o/r", "tok")

        assertNull(snapshot.error)
        assertEquals(1, snapshot.badEntries)
        assertEquals(1, snapshot.runs.size)
        assertEquals(77L, snapshot.runs.first().id)
        assertEquals("success", snapshot.runs.first().conclusion)
        assertTrue(fetch.urls.first().contains("/repos/o/r/actions/runs?per_page="))
    }

    @Test
    fun runsBadRepoShapeStaysOffline() {
        val fetch = FakeFetch()
        val snapshot = client(fetch).runs("bad", null)

        assertTrue(snapshot.error!!.contains("owner/name"))
        assertEquals(0, fetch.urls.size)
    }

    @Test
    fun runJobsParsesIdNameStatusAndCountsBad() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(
            200,
            "{\"jobs\":[{\"id\":105,\"name\":\"编译 + 测试\",\"status\":\"completed\"," +
                "\"conclusion\":\"failure\",\"started_at\":\"2026-09-17T14:52:03Z\"}," +
                "{\"junk\":1}]}",
        )
        val list = client(fetch).runJobs("o/r", 77, "tok")

        assertNull(list.error)
        assertEquals(1, list.badEntries)
        assertEquals(1, list.jobs.size)
        assertEquals(105L, list.jobs.first().id)
        assertEquals("failure", list.jobs.first().conclusion)
        assertTrue(fetch.urls.first().contains("/repos/o/r/actions/runs/77/jobs?per_page=20"))
    }

    @Test
    fun runJobs404SaysRunMayBeCleaned() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(404, "gone")
        val list = client(fetch).runJobs("o/r", 77, null)

        assertTrue(list.error!!.contains("404"))
    }

    @Test
    fun jobLogReadsTextAndFlagsTruncation() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(200, "line1\nline2\nline3", truncated = true)
        val log = client(fetch).jobLog("o/r", 105, "tok")

        assertNull(log.error)
        assertEquals("line1\nline2\nline3", log.text)
        assertEquals(17, log.charCount)
        assertTrue(log.truncated)
        assertTrue(fetch.urls.first().contains("/repos/o/r/actions/jobs/105/logs"))
    }

    @Test
    fun jobLogEmptyBodySaysSoInsteadOfFakeSuccess() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(200, "")
        val log = client(fetch).jobLog("o/r", 105, null)

        assertNull(log.text)
        assertNotNull(log.error)
        assertTrue(log.error!!.contains("空"))
    }
/** ── 404 自愈（旧 Agora 的病：模型爱传 ref=main，撞上 master 仓就 404 来回试错）── */

private class RoutingFetch : (String, String?, String?, Int) -> GitHubHttpResult {
    val calls = ArrayList<String>()
    override fun invoke(url: String, token: String?, accept: String?, maxChars: Int): GitHubHttpResult {
        calls += url
        return when {
            url.endsWith("/repos/some-owner/some-repo") ->
                GitHubHttpResult(200, """{"default_branch":"master"}""")
            url.contains("/contents/scripts/_check_color.py") && url.contains("ref=main") ->
                GitHubHttpResult(404, """{"message":"No commit found for the ref main"}""")
            url.contains("/contents/scripts/_check_color.py") && url.contains("ref=master") ->
                GitHubHttpResult(200, """{"name":"_check_color.py","path":"scripts/_check_color.py","sha":"abc123","size":50,"encoding":"base64","content":"cHJpbnQoImhlbGxvIikK"}""")
            url.contains("/contents/scripts") && url.contains("ref=master") ->
                GitHubHttpResult(200, """[{"name":"_check_color.py","path":"scripts/_check_color.py","type":"file","size":50}]""")
            url.contains("/contents/scripts/_check_color.py") ->
                GitHubHttpResult(404, """{"message":"Not Found"}""")
            url.contains("/commits") && url.contains("sha=main") ->
                GitHubHttpResult(404, """{"message":"No commit found for the ref main"}""")
            url.contains("/commits") && url.contains("sha=master") ->
                GitHubHttpResult(200, """[{"sha":"c1","commit":{"message":"first","author":{"name":"a","date":"2026-09-26"}}}]""")
            else -> GitHubHttpResult(404, """{"message":"unexpected ${url.takeLast(40)}"}""")
        }
    }
}

@Test
fun `readFile 404 自愈：main 不存在探默认分支 master 重试成功`() {
    val fetch = RoutingFetch()
    val c = GitHubRepoClient(fetch)
    val out = c.readFile("some-owner/some-repo", "scripts/_check_color.py", "main", "t")
    assertNull(out.error)
    assertEquals("""print("hello")""", out.text?.trim())
    // 序列钉住：先按 main 打 404，再探默认分支，再用 master 重试——不多打
    assertTrue(fetch.calls.any { it.contains("ref=main") })
    assertTrue(fetch.calls.any { it.endsWith("/repos/some-owner/some-repo") })
    assertTrue(fetch.calls.any { it.contains("ref=master") })
}

@Test
fun `readFile 404 且 ref 恰是默认分支：不重试，文案带默认分支`() {
    val fetch = RoutingFetch()
    val c = GitHubRepoClient(fetch)
    val out = c.readFile("some-owner/some-repo", "不存在.py", "master", "t")
    assertNotNull(out.error)
    assertTrue("文案要指路默认分支：" + out.error, out.error!!.contains("master"))
}

@Test
fun `browse 404 自愈：同 main 病自动切默认分支`() {
    val fetch = RoutingFetch()
    val c = GitHubRepoClient(fetch)
    val out = c.browse("some-owner/some-repo", "scripts", "main", "t")
    assertNull(out.error)
}

@Test
fun `listCommits 404 自愈：切默认分支重取`() {
    val fetch = RoutingFetch()
    val c = GitHubRepoClient(fetch)
    val out = c.listCommits("some-owner/some-repo", "main", null, "t", 10)
    assertNull(out.error)
    assertEquals(1, out.commits.size)
}

}