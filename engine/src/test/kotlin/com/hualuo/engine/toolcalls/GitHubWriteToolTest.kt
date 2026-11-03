package com.hualuo.engine.toolcalls

import com.hualuo.engine.github.GitHubHttpResult
import com.hualuo.engine.github.GitHubRepoClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写类工具的纯 JVM 契约测试：闸门点头才写、拒绝/超时/闸门炸了什么都不写、
 * 碰网之前挡下缺参/超限/缺令牌、写失败把 GitHub 的人话照传。
 * putJson 注入，绝不碰真网。
 *
 * 修记（run 35482556874）：首版这里把默认仓库写成了 null，而测试参数里又没给 repo——
 * 六条测试在「缺参数 repo」这一步就挂了，根本没走到闸门（脚手架错伪装成红）。
 * 默认改成 o/r：这批测试的意图本来就是「配好了仓库，走完整提议与点头流程」。
 */
class GitHubWriteToolTest {

    private class FakePut : (String, String?, String) -> GitHubHttpResult {
        val urls = ArrayList<String>()
        var next = GitHubHttpResult(200, """{"commit":{"sha":"c0ffee1234"},"content":{"sha":"aaa"}}""")

        override fun invoke(url: String, token: String?, jsonBody: String): GitHubHttpResult {
            urls += url
            return next
        }
    }

    private fun registryOf(
        put: FakePut,
        confirmer: WriteConfirmer,
        token: String? = "tok",
        repo: String? = "o/r",
    ): ToolRegistry {
        val registry = ToolRegistry()
        GitHubWriteTool.register(
            registry = registry,
            loadToken = { token },
            defaultRepo = { repo },
            repoClient = GitHubRepoClient(putJson = put),
            confirmer = confirmer,
        )
        return registry
    }

    private fun args(
        path: String = "a.txt",
        content: String = "hello",
        message: String = "改一下",
        sha: String? = null,
    ): String = buildString {
        append("{\"path\":\"").append(path).append("\",\"content\":\"").append(content)
            .append("\",\"message\":\"").append(message).append("\"")
        if (sha != null) append(",\"sha\":\"").append(sha).append("\"")
        append("}")
    }

    @Test
    fun approvedWriteGoesThroughAndReportsCommit() {
        val put = FakePut()
        val registry = registryOf(put, WriteConfirmer { true })
        val outcome = registry.execute("github_update_file", args())

        assertTrue("点头后应成功：${outcome.text}", outcome.ok)
        assertTrue("回报里要有 commit：${outcome.text}", outcome.text.contains("c0ffee"))
        assertEquals(1, put.urls.size)
    }

    @Test
    fun rejectedWriteTouchesNothing() {
        val put = FakePut()
        val registry = registryOf(put, WriteConfirmer { false })
        val outcome = registry.execute("github_update_file", args())

        assertFalse(outcome.ok)
        assertTrue("拒绝要说清什么都没改：${outcome.text}", outcome.text.contains("什么都没改"))
        assertEquals("拒绝不许碰网", 0, put.urls.size)
    }

    @Test
    fun confirmerThrowIsTreatedAsReject() {
        val put = FakePut()
        val registry = registryOf(put, WriteConfirmer { throw IllegalStateException("卡崩了") })
        val outcome = registry.execute("github_update_file", args())

        assertFalse(outcome.ok)
        assertTrue("闸门炸了按拒收场：${outcome.text}", outcome.text.contains("没有确认"))
        assertEquals(0, put.urls.size)
    }

    @Test
    fun missingMessageFailsBeforeConfirmation() {
        val put = FakePut()
        var asked = false
        val registry = registryOf(put, WriteConfirmer { asked = true; true })
        val outcome = registry.execute("github_update_file", """{"path":"a.txt","content":"x"}""")

        assertFalse(outcome.ok)
        assertTrue("缺 message 要指名：${outcome.text}", outcome.text.contains("message"))
        assertFalse("缺参不该先弹卡", asked)
        assertEquals(0, put.urls.size)
    }

    @Test
    fun oversizedContentRefusedBeforeConfirmation() {
        val put = FakePut()
        var asked = false
        val registry = registryOf(put, WriteConfirmer { asked = true; true })
        val big = "x".repeat(GitHubWriteTool.MAX_WRITE_CHARS + 1)
        val outcome = registry.execute("github_update_file", args(content = big))

        assertFalse(outcome.ok)
        assertTrue("超限要说上限：${outcome.text}", outcome.text.contains("上限"))
        assertFalse(asked)
        assertEquals(0, put.urls.size)
    }

    @Test
    fun missingTokenFailsBeforeConfirmation() {
        val put = FakePut()
        var asked = false
        val registry = registryOf(put, WriteConfirmer { asked = true; true }, token = null)
        val outcome = registry.execute("github_update_file", args())

        assertFalse(outcome.ok)
        assertTrue("缺令牌要指出路：${outcome.text}", outcome.text.contains("令牌"))
        assertFalse(asked)
    }

    @Test
    fun proposalCarriesWhatTheChipNeeds() {
        val proposal = arrayOfNulls<GitHubWriteProposal>(1)
        val put = FakePut()
        val registry = registryOf(put, WriteConfirmer { p -> proposal[0] = p; true })
        registry.execute("github_update_file", args(path = "src/X.kt", content = "fun x() {}", message = "new fn", sha = "abc"))

        val p = proposal[0] ?: error("闸门必须收到提议")
        assertEquals("src/X.kt", p.path)
        assertEquals("new fn", p.message)
        assertFalse("带 sha = 覆盖已有文件", p.isNewFile)
        assertEquals("fun x() {}".length, p.contentChars)
        assertTrue(p.contentPreview.contains("fun x"))
    }

    @Test
    fun githubConflictErrorIsPassedThrough() {
        val put = FakePut()
        put.next = GitHubHttpResult(409, "conflict")
        val registry = registryOf(put, WriteConfirmer { true })
        val outcome = registry.execute("github_update_file", args(sha = "abc"))

        assertFalse(outcome.ok)
        assertTrue("409 要说重开重改：${outcome.text}", outcome.text.contains("重开") || outcome.text.contains("409"))
    }
}
