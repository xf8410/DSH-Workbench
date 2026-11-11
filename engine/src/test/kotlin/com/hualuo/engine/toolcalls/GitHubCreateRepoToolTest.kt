package com.hualuo.engine.toolcalls

import com.hualuo.engine.github.GitHubHttpResult
import com.hualuo.engine.github.GitHubRepoCreator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 建仓工具的纯 JVM 契约：成功回仓名与链接、缺令牌不碰网、名字被占用说人话、
 * 名字不合规在碰网前就拒。postJson 注入，绝不碰真网。
 */
class GitHubCreateRepoToolTest {

    private class FakePost : (String, String?, String) -> GitHubHttpResult {
        val urls = ArrayList<String>()
        val bodies = ArrayList<String>()
        var next = GitHubHttpResult(
            201,
            """{"full_name":"o/new-repo","html_url":"https://github.com/o/new-repo","default_branch":"main"}""",
        )

        override fun invoke(url: String, token: String?, body: String): GitHubHttpResult {
            urls += url
            bodies += body
            return next
        }
    }

    private fun registryOf(post: FakePost, token: String? = "tok"): ToolRegistry {
        val registry = ToolRegistry()
        GitHubCreateRepoTool.register(registry, { token }, GitHubRepoCreator(post))
        return registry
    }

    @Test
    fun createsPrivateRepoAndReportsIt() {
        val post = FakePost()
        val outcome = registryOf(post).execute(
            "github_create_repo",
            """{"name":"new-repo","private":true,"description":"实验仓"}""",
        )

        assertTrue("建仓应成功：${outcome.text}", outcome.ok)
        assertTrue("结果要带仓名：${outcome.text}", outcome.text.contains("o/new-repo"))
        assertTrue("结果要说清是私有：${outcome.text}", outcome.text.contains("私有"))
        assertEquals(1, post.urls.size)
        assertTrue("建仓打的是 /user/repos：${post.urls[0]}", post.urls[0].endsWith("/user/repos"))
        assertTrue("请求体要带私有标记：${post.bodies[0]}", post.bodies[0].contains("\"private\":true"))
        assertTrue("请求体要带名字：${post.bodies[0]}", post.bodies[0].contains("\"name\":\"new-repo\""))
        assertFalse("请求体不该漏令牌", post.bodies[0].contains("tok"))
    }

    @Test
    fun missingTokenStaysOffline() {
        val post = FakePost()
        val outcome = registryOf(post, token = null).execute("github_create_repo", """{"name":"new-repo"}""")

        assertFalse(outcome.ok)
        assertTrue("要指出令牌的出路：${outcome.text}", outcome.text.contains("令牌"))
        assertEquals("没令牌不许碰网", 0, post.urls.size)
    }

    @Test
    fun nameTakenIsExplained() {
        val post = FakePost().apply { next = GitHubHttpResult(422, """{"message":"name already exists"}""") }
        val outcome = registryOf(post).execute("github_create_repo", """{"name":"taken"}""")

        assertFalse(outcome.ok)
        assertTrue("422 要说名字被占用：${outcome.text}", outcome.text.contains("占用"))
        assertEquals(1, post.urls.size)
    }

    @Test
    fun invalidNameRefusedBeforeNetwork() {
        val post = FakePost()
        val outcome = registryOf(post).execute("github_create_repo", """{"name":"bad name!"}""")

        assertFalse(outcome.ok)
        assertTrue("不合规的名字要说清楚：${outcome.text}", outcome.text.contains("不合法"))
        assertEquals("名字不合规不许碰网", 0, post.urls.size)
    }
}