package com.hualuo.engine.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub 令牌登录验证的纯 JVM 契约：空令牌发网之前拦下、成功记登录名与权限、
 * 没带权限头如实说「没声明」、401/403/断网都有带出路的原话、令牌只进请求头。
 * fetch 注入，绝不碰真网。
 */
class GitHubAuthClientTest {

    private class FakeFetch : (String, String?, String?, Int) -> GitHubHttpResult {
        val urls = ArrayList<String>()
        val tokens = ArrayList<String?>()
        var next = GitHubHttpResult(200, """{"login":"xf8410"}""", false, "repo, workflow")

        override fun invoke(url: String, token: String?, accept: String?, maxChars: Int): GitHubHttpResult {
            urls += url
            tokens += token
            return next
        }
    }

    @Test
    fun blankTokenIsRefusedWithoutTouchingNetwork() {
        val fetch = FakeFetch()
        val outcome = GitHubAuthClient(fetch).verify("   ")

        assertTrue(outcome is GitHubAuthOutcome.Failed)
        val reason = (outcome as GitHubAuthOutcome.Failed).reason
        assertTrue("空令牌要指出怎么填：$reason", reason.contains("令牌是空的"))
        assertEquals("空令牌不许碰网", 0, fetch.urls.size)
    }

    @Test
    fun successReadsLoginAndScopesAndProvesTokenWentIntoTheHeader() {
        val fetch = FakeFetch()
        val outcome = GitHubAuthClient(fetch).verify("tok-abc")

        assertTrue(outcome is GitHubAuthOutcome.Ok)
        val identity = (outcome as GitHubAuthOutcome.Ok).identity
        assertEquals("xf8410", identity.login)
        assertEquals(listOf("repo", "workflow"), identity.scopes)
        assertTrue(identity.scopesDeclared)
        assertTrue("打的是 /user：${fetch.urls}", fetch.urls.first().endsWith("/user"))
        assertEquals("令牌只进请求头（fetch 第三参之外的 token 位）", "tok-abc", fetch.tokens.first())
    }

    @Test
    fun missingScopeHeaderMeansNotDeclaredNotFailure() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(200, """{"login":"octocat"}""", false, "")
        val outcome = GitHubAuthClient(fetch).verify("tok")

        assertTrue(outcome is GitHubAuthOutcome.Ok)
        val identity = (outcome as GitHubAuthOutcome.Ok).identity
        assertEquals("octocat", identity.login)
        assertFalse("没带权限头是「没声明」，不是「没权限」", identity.scopesDeclared)
        assertTrue(identity.scopes.isEmpty())
    }

    @Test
    fun unauthorizedCarriesRegenerationAdvice() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(401, "Bad credentials")
        val outcome = GitHubAuthClient(fetch).verify("bad")

        assertTrue(outcome is GitHubAuthOutcome.Failed)
        val reason = (outcome as GitHubAuthOutcome.Failed).reason
        assertTrue("401 要说核对与重生成：$reason", reason.contains("401"))
        assertTrue(reason.contains("重新生成") || reason.contains("过期"))
    }

    @Test
    fun forbiddenSaysRateLimitOrRevoked() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(403, "rate limited")
        val outcome = GitHubAuthClient(fetch).verify("tok")

        assertTrue(outcome is GitHubAuthOutcome.Failed)
        val reason = (outcome as GitHubAuthOutcome.Failed).reason
        assertTrue("403 要给出路：$reason", reason.contains("403"))
    }

    @Test
    fun networkDownIsHumanAndNamesTheCause() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(0, "connect timed out")
        val outcome = GitHubAuthClient(fetch).verify("tok")

        assertTrue(outcome is GitHubAuthOutcome.Failed)
        val reason = (outcome as GitHubAuthOutcome.Failed).reason
        assertTrue("断网要带原因：$reason", reason.contains("连不上"))
    }

    @Test
    fun unreadableBodySaysSoInsteadOfInventingALogin() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(200, "not json at all", false, "repo")
        val outcome = GitHubAuthClient(fetch).verify("tok")

        assertTrue(outcome is GitHubAuthOutcome.Failed)
        val reason = (outcome as GitHubAuthOutcome.Failed).reason
        assertTrue("读不懂要明说：$reason", reason.contains("读不懂"))
    }

    @Test
    fun loginMissingFromBodyIsNotATokenFailureButIsStillVoiced() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(200, """{"id":1}""", false, "repo")
        val outcome = GitHubAuthClient(fetch).verify("tok")

        assertTrue(outcome is GitHubAuthOutcome.Failed)
        val reason = (outcome as GitHubAuthOutcome.Failed).reason
        assertTrue("缺 login 要说清：$reason", reason.contains("login"))
    }
}
