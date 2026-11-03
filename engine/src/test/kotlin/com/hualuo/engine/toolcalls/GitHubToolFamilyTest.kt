package com.hualuo.engine.toolcalls

import com.hualuo.engine.github.GitHubHttpResult
import com.hualuo.engine.github.GitHubRepoClient
import com.hualuo.engine.github.GitHubSearchClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub 工具族（读类）的纯 JVM 契约测试：注册面（只有读类，写类一个都不许进）、
 * 离线闸（缺令牌/缺参数一律不发网）、默认仓库兜底、令牌执行时现场读、
 * 结果文本的账（坏条目计数、截断出声、403 归因）。fetch 注入，绝不碰真网。
 */
class GitHubToolFamilyTest {

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

    private fun familyOf(
        fetch: FakeFetch,
        token: String? = "tok",
        repo: String? = null,
    ): ToolRegistry = GitHubToolFamily.build(
        loadToken = { token },
        defaultRepo = { repo },
        repoClient = GitHubRepoClient(fetch = fetch),
        searchClient = GitHubSearchClient(fetch),
    )

    @Test
    fun familyRegistersTheReadToolsOnly() {
        val registry = familyOf(FakeFetch())
        val names = registry.specs().map { it.name }.toSet()
        assertEquals(
            setOf(
                "github_list_my_repos",
                "github_list_user_repos",
                "github_browse_repo",
                "github_read_file",
                "github_search_code",
                "github_list_branches",
                "github_list_commits",
                "github_ci_runs",
                "github_ci_jobs",
                "github_ci_job_log",
            ),
            names,
        )
        names.forEach { name ->
            val lowered = name.lowercase()
            assertFalse(
                "写类工具不许进这张表：$name",
                lowered.contains("update") || lowered.contains("write") ||
                    lowered.contains("delete") || lowered.contains("merge") ||
                    lowered.contains("create") || lowered.contains("put"),
            )
        }
    }

    @Test
    fun listMyReposWithoutTokenStaysOffline() {
        val fetch = FakeFetch()
        val outcome = familyOf(fetch, token = null).execute("github_list_my_repos", "{}")

        assertFalse(outcome.ok)
        assertTrue("要指出缺令牌的出路：${outcome.text}", outcome.text.contains("令牌"))
        assertEquals("没令牌不许碰网", 0, fetch.urls.size)
    }

    @Test
    fun browseFallsBackToDefaultRepoAndSendsToken() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(200, """[{"name":"a.kt","path":"a.kt","type":"file","size":10}]""")
        val outcome = familyOf(fetch, token = "tok-abc", repo = "o/r").execute("github_browse_repo", "{}")

        assertTrue(outcome.ok)
        assertTrue("目录条目要列出来：${outcome.text}", outcome.text.contains("a.kt"))
        assertTrue(fetch.urls.first().contains("/repos/o/r/contents"))
        assertEquals("令牌要传给客户端（只进请求头）", "tok-abc", fetch.tokens.first())
    }

    @Test
    fun browseWithoutRepoAndWithoutDefaultFailsOffline() {
        val fetch = FakeFetch()
        val outcome = familyOf(fetch, repo = null).execute("github_browse_repo", "{}")

        assertFalse(outcome.ok)
        assertTrue("缺仓库要指名：${outcome.text}", outcome.text.contains("repo"))
        assertEquals(0, fetch.urls.size)
    }

    @Test
    fun readFileWithoutPathFailsOffline() {
        val fetch = FakeFetch()
        val outcome = familyOf(fetch, repo = "o/r").execute("github_read_file", "{}")

        assertFalse(outcome.ok)
        assertTrue("缺 path 要指名：${outcome.text}", outcome.text.contains("path"))
        assertEquals(0, fetch.urls.size)
    }

    @Test
    fun garbageArgumentsTreatedAsEmptyObject() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(200, "[]")
        val outcome = familyOf(fetch).execute("github_list_my_repos", "这不是json")

        assertTrue("参数手滑不许把整轮弄炸：${outcome.text}", outcome.ok)
        assertTrue(fetch.urls.first().contains("/user/repos"))
    }

    @Test
    fun tokenIsReadAtExecutionTimeNotAtBuildTime() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(200, "[]")
        var token: String? = "tok-1"
        val registry = GitHubToolFamily.build(
            loadToken = { token },
            defaultRepo = { null },
            repoClient = GitHubRepoClient(fetch = fetch),
            searchClient = GitHubSearchClient(fetch),
        )

        registry.execute("github_list_my_repos", "{}")
        token = "tok-2"
        registry.execute("github_list_my_repos", "{}")

        assertEquals("设置改了下一句就用新的", listOf("tok-1", "tok-2"), fetch.tokens)
    }

    @Test
    fun ciJobsWithoutRunIdFailsOffline() {
        val fetch = FakeFetch()
        val outcome = familyOf(fetch, repo = "o/r").execute("github_ci_jobs", "{}")

        assertFalse(outcome.ok)
        assertTrue("缺 run_id 要指名：${outcome.text}", outcome.text.contains("run_id"))
        assertEquals(0, fetch.urls.size)
    }

    @Test
    fun jobLogShowsTruncationOutLoud() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(200, "line-1\nline-2", truncated = true)
        val outcome = familyOf(fetch, repo = "o/r").execute("github_ci_job_log", """{"job_id":7}""")

        assertTrue(outcome.ok)
        assertTrue("日志正文要在：${outcome.text}", outcome.text.contains("line-1"))
        assertTrue("截断要出声：${outcome.text}", outcome.text.contains("截断"))
    }

    @Test
    fun searchWithoutTokenStaysOffline() {
        val fetch = FakeFetch()
        val outcome = familyOf(fetch, token = null, repo = "o/r")
            .execute("github_search_code", """{"keyword":"LRow"}""")

        assertFalse(outcome.ok)
        assertTrue("搜索没令牌要说死规矩：${outcome.text}", outcome.text.contains("令牌"))
        assertEquals(0, fetch.urls.size)
    }

    @Test
    fun search403CarriesQuotaAttribution() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(403, "rate limited")
        val outcome = familyOf(fetch, repo = "o/r")
            .execute("github_search_code", """{"keyword":"x"}""")

        assertFalse(outcome.ok)
        assertTrue("403 要归因到配额与等待：${outcome.text}", outcome.text.contains("403"))
    }

    @Test
    fun browseCountsBadEntriesOutLoud() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(
            200,
            """[{"name":"good.kt","path":"good.kt","type":"file","size":1},{"broken":true}]""",
        )
        val outcome = familyOf(fetch, repo = "o/r").execute("github_browse_repo", "{}")

        assertTrue(outcome.ok)
        assertTrue(outcome.text.contains("good.kt"))
        assertTrue("坏条目计数要出声：${outcome.text}", outcome.text.contains("读不懂"))
    }
}
