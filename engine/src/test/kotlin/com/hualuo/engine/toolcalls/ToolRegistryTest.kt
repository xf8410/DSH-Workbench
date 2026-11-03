package com.hualuo.engine.toolcalls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具注册表与喂回裁剪的纯 JVM 测试：
 *  - 注册名字规约（坏名字开发期就炸）；
 *  - 未注册的名字、执行抛异常，都折成给模型看的失败文本（不抛穿）；
 *  - 结果裁剪封顶且带账（不悄悄丢中间）。
 */
class ToolRegistryTest {

    private fun spec(name: String) = ToolSpec(
        name = name,
        description = "测试用",
        parametersJson = """{"type":"object","properties":{}}""",
    )

    @Test
    fun registerAndListSpecs() {
        val registry = ToolRegistry()
        registry.register(spec("list_repos")) { "ok" }
        registry.register(spec("read-file")) { "ok" }

        assertEquals(listOf("list_repos", "read-file"), registry.specs().map { it.name })
        assertEquals(2, registry.size())
        assertFalse(registry.isEmpty())
    }

    @Test
    fun badNameIsRejectedAtRegisterTime() {
        val registry = ToolRegistry()
        val boom = runCatching { registry.register(spec("bad name!")) { "x" } }
        assertTrue("坏名字必须开发期炸：$boom", boom.isFailure)
    }

    @Test
    fun unknownToolGetsAVoiceNotAnException() {
        val registry = ToolRegistry()
        registry.register(spec("list_repos")) { "ok" }

        val outcome = registry.execute("no_such_tool", "{}")

        assertFalse(outcome.ok)
        assertTrue("要说清不存在：${outcome.text}", outcome.text.contains("不存在"))
        assertTrue("还要报现在有哪些：${outcome.text}", outcome.text.contains("list_repos"))
    }

    @Test
    fun handlerExceptionBecomesFailureText() {
        val registry = ToolRegistry()
        registry.register(spec("boom_tool")) { throw IllegalStateException("网络炸了") }

        val outcome = registry.execute("boom_tool", "{}")

        assertFalse(outcome.ok)
        assertTrue(outcome.text.contains("boom_tool"))
        assertTrue(outcome.text.contains("网络炸了"))
    }

    @Test
    fun handlerReceivesRawArguments() {
        val registry = ToolRegistry()
        var seen = ""
        registry.register(spec("echo")) { args -> seen = args; "ok" }

        val outcome = registry.execute("echo", """{"q":"你好"}""")

        assertTrue(outcome.ok)
        assertEquals("""{"q":"你好"}""", seen)
    }

    @Test
    fun trimmerCapsLongResultsWithAnAccount() {
        val long = "x".repeat(ToolResultTrimmer.RESULT_LIMIT + 500)
        val trimmed = ToolResultTrimmer.trim(long)

        assertTrue(trimmed.length < long.length)
        assertTrue("要留头部：${trimmed.take(20)}", trimmed.startsWith("xxxx"))
        assertTrue("要说省了多少字：$trimmed", trimmed.contains("省了 500 字"))
    }

    @Test
    fun trimmerLeavesShortResultsUntouched() {
        val short = "小小结果"
        assertEquals(short, ToolResultTrimmer.trim(short))
    }
}
