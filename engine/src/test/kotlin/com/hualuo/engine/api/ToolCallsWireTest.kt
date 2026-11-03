package com.hualuo.engine.api

import com.hualuo.engine.generation.GenerationSlot
import com.hualuo.engine.generation.IdleWatchdog
import com.hualuo.engine.http.RetryPolicy
import com.hualuo.engine.toolcalls.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具协议在请求/响应两侧的形状测试（纯 JVM，脚本化假 transport）：
 *  - 请求体：tools 数组在、消息里的 tool_calls 与 tool_call_id 形状对；
 *  - 响应：流式碎片装配、非流式 message 形状、length 截断不冒充成品；
 *  - 老形状（不带 tools）回复工具调用按形状异常报错——不静默吞；
 *  - 断流证据：写到一半工具调用就断，IncompleteStream 要带上「当时正在写工具调用」。
 * 流程行为（重试红线/槽/卡死）仍归 ChatWireRunnerTest，不重。
 */
class ToolCallsWireTest {

    private class ScriptTransport(private val responder: (WireRequest, LineSink) -> WireResponse) : WireTransport {
        var calls = 0
        val seen = mutableListOf<WireRequest>()
        override fun exchange(request: WireRequest, sink: LineSink): WireResponse {
            seen += request
            calls += 1
            return responder(request, sink)
        }

        override fun cancel() {}
        override fun isCancelled(): Boolean = false
    }

    private val profile = ProviderProfile(
        name = "测试端",
        baseUrl = "https://gw.example.com",
        apiKey = "sk-test",
        model = "m1",
    )

    private fun clientOf(transport: ScriptTransport): OpenAiCompatClient = OpenAiCompatClient(
        transport = transport,
        slot = GenerationSlot(),
        policy = RetryPolicy(maxAutomaticRetries = 0, backoffBaseMs = 1L, backoffMaxMs = 2L),
        watchdog = IdleWatchdog(IdleWatchdog.GENERATION_IDLE_MS),
        sleeper = {},
    )

    private val toolSpec = ToolSpec(
        name = "list_repos",
        description = "列出我的仓库",
        parametersJson = """{"type":"object","properties":{"limit":{"type":"integer"}}}""",
    )

    @Test
    fun toolsArrayGoesIntoRequestBodyAndSchemaStaysIntact() {
        val transport = ScriptTransport { _, sink ->
            sink.onLine("""data: {"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}""")
            WireResponse(200, null, 10L, null)
        }

        val outcome = clientOf(transport).chatTurns(profile, listOf(ChatTurn("user", "列一下")), tools = listOf(toolSpec)) { }

        assertTrue(outcome is ChatOutcome.Text)
        val body = Json.parseToJsonElement(transport.seen.single().body!!).jsonObject
        val tool = body.getValue("tools").jsonArray.single().jsonObject
        assertEquals("function", tool.getValue("type").jsonPrimitive.contentOrNull)
        val fn = tool.getValue("function").jsonObject
        assertEquals("list_repos", fn.getValue("name").jsonPrimitive.contentOrNull)
        assertEquals("列出我的仓库", fn.getValue("description").jsonPrimitive.contentOrNull)
        assertEquals(
            "integer",
            fn.getValue("parameters").jsonObject
                .getValue("properties").jsonObject
                .getValue("limit").jsonObject.getValue("type").jsonPrimitive.contentOrNull,
        )
    }

    @Test
    fun toolCallTurnsKeepIdsAndShapes() {
        val transport = ScriptTransport { _, sink ->
            sink.onLine("""data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""")
            WireResponse(200, null, 10L, null)
        }
        val history = listOf(
            ChatTurn("user", "列出仓库"),
            ChatTurn(
                "assistant",
                "",
                toolCalls = listOf(WireToolCall("call_1", "list_repos", """{"limit":5}""")),
            ),
            ChatTurn("tool", "仓库清单：a、b", toolCallId = "call_1"),
            ChatTurn("user", "继续"),
        )

        clientOf(transport).chatTurns(profile, history, tools = listOf(toolSpec)) { }

        val messages = Json.parseToJsonElement(transport.seen.single().body!!).jsonObject
            .getValue("messages").jsonArray
        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant.getValue("role").jsonPrimitive.contentOrNull)
        assertTrue("content 键必须在（空串）", assistant.containsKey("content"))
        val call = assistant.getValue("tool_calls").jsonArray.single().jsonObject
        assertEquals("call_1", call.getValue("id").jsonPrimitive.contentOrNull)
        assertEquals("function", call.getValue("type").jsonPrimitive.contentOrNull)
        assertEquals("list_repos", call.getValue("function").jsonObject.getValue("name").jsonPrimitive.contentOrNull)
        val tool = messages[2].jsonObject
        assertEquals("tool", tool.getValue("role").jsonPrimitive.contentOrNull)
        assertEquals("call_1", tool.getValue("tool_call_id").jsonPrimitive.contentOrNull)
    }

    @Test
    fun streamedFragmentsAssembleIntoToolCallsOutcome() {
        val transport = ScriptTransport { _, sink ->
            sink.onLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_9","function":{"name":"list","arguments":"{\"a\":"}}]}}]}""")
            sink.onLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]}}]}""")
            sink.onLine("""data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""")
            WireResponse(200, null, 30L, null)
        }

        val outcome = clientOf(transport).chatTurns(profile, listOf(ChatTurn("user", "x")), tools = listOf(toolSpec)) { }

        assertTrue("该回工具调用：$outcome", outcome is ChatOutcome.Calls)
        val calls = (outcome as ChatOutcome.Calls).calls
        assertEquals(1, calls.size)
        assertEquals("call_9", calls[0].id)
        assertEquals("list", calls[0].name)
        assertEquals("""{"a":1}""", calls[0].argumentsJson)
    }

    @Test
    fun lengthTruncationBeatsToolCalls() {
        // 被 length 截断的参数多半是半份 JSON：宁可按截断报，不把坏参数递去执行
        val transport = ScriptTransport { _, sink ->
            sink.onLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_x","function":{"name":"big","arguments":"{\"a\":"}}]}}]}""")
            sink.onLine("""data: {"choices":[{"delta":{},"finish_reason":"length"}]}""")
            WireResponse(200, null, 30L, null)
        }

        val outcome = clientOf(transport).chatTurns(profile, listOf(ChatTurn("user", "x")), tools = listOf(toolSpec)) { }

        assertTrue("必须按截断报：$outcome", outcome is ChatOutcome.Failed)
        assertTrue((outcome as ChatOutcome.Failed).error is GenerationError.OutputTruncated)
    }

    @Test
    fun brokenStreamWhileWritingToolCallSaysSoInEvidence() {
        // 写到一半工具调用（碎片到了、收尾标记没来）就断：证据位必须为真，话要说得出来
        val transport = ScriptTransport { _, sink ->
            sink.onLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_half","function":{"name":"half","arguments":"{\"a\":"}}]}}]}""")
            WireResponse(200, null, 30L, null) // 无 finish_reason / 无 [DONE]：可证明的没说完
        }

        val outcome = clientOf(transport).chatTurns(profile, listOf(ChatTurn("user", "x")), tools = listOf(toolSpec)) { }

        assertTrue("必须按半路断收：$outcome", outcome is ChatOutcome.Failed)
        val error = (outcome as ChatOutcome.Failed).error
        assertTrue("要是 IncompleteStream：$error", error is GenerationError.IncompleteStream)
        val message = error.userMessage()
        assertTrue("要带「正在写工具调用」证据：$message", message.contains("正在写一个工具调用"))
    }

    @Test
    fun nonStreamingMessageShapeAlsoCarriesToolCalls() {
        val transport = ScriptTransport { _, sink ->
            sink.onLine(
                """data: {"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_n","type":"function","function":{"name":"one","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
            )
            WireResponse(200, null, 40L, null)
        }

        val outcome = clientOf(transport).chatTurns(profile, listOf(ChatTurn("user", "x")), tools = listOf(toolSpec)) { }

        assertTrue("非流式 message 形状也要认：$outcome", outcome is ChatOutcome.Calls)
        assertEquals("one", (outcome as ChatOutcome.Calls).calls.single().name)
    }

    @Test
    fun legacyChatWithoutToolsRefusesToolCallReplies() {
        val transport = ScriptTransport { _, sink ->
            sink.onLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"x","arguments":"{}"}}]}}]}""")
            sink.onLine("""data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""")
            WireResponse(200, null, 10L, null)
        }

        val error = clientOf(transport).chat(profile, listOf(ChatTurn("user", "x"))) { }

        assertTrue("没带清单却回调用：按形状异常报，不装没看见：$error", error is GenerationError.Configuration)
    }

    @Test
    fun badSchemaFallsBackToEmptyObjectInsteadOfKillingTheRequest() {
        val transport = ScriptTransport { _, sink ->
            sink.onLine("""data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""")
            WireResponse(200, null, 10L, null)
        }
        val brokenSpec = toolSpec.copy(parametersJson = "{not json at all")

        clientOf(transport).chatTurns(profile, listOf(ChatTurn("user", "x")), tools = listOf(brokenSpec)) { }

        val fn = Json.parseToJsonElement(transport.seen.single().body!!).jsonObject
            .getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject
        // 坏 schema 换成空对象兜底：请求照发，形状是对的
        assertEquals("object", fn.getValue("parameters").jsonObject.getValue("type").jsonPrimitive.contentOrNull)
    }

    @Test
    fun emptyToolsListKeepsOldRequestShape() {
        val transport = ScriptTransport { _, sink ->
            sink.onLine("""data: {"choices":[{"delta":{"content":"1"},"finish_reason":"stop"}]}""")
            WireResponse(200, null, 10L, null)
        }

        clientOf(transport).chatTurns(profile, listOf(ChatTurn("user", "x")), tools = emptyList()) { }

        val body = Json.parseToJsonElement(transport.seen.single().body!!).jsonObject
        assertTrue("空清单不发 tools 字段（老行为不变）", !body.containsKey("tools"))
    }

    @Test
    fun plainTextOutcomeStaysTextEvenWithToolsOffered() {
        val transport = ScriptTransport { _, sink ->
            sink.onLine("""data: {"choices":[{"delta":{"content":"你好"},"finish_reason":"stop"}]}""")
            WireResponse(200, null, 10L, null)
        }
        val text = StringBuilder()

        val outcome = clientOf(transport).chatTurns(profile, listOf(ChatTurn("user", "x")), tools = listOf(toolSpec)) { text.append(it) }

        assertTrue(outcome is ChatOutcome.Text)
        assertEquals("你好", text.toString())
    }
}
