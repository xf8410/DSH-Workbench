package com.hualuo.engine.api

import com.hualuo.engine.generation.GenerationSlot
import com.hualuo.engine.generation.IdleWatchdog
import com.hualuo.engine.http.RetryPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenAI 兼容客户端的契约测试：钉的是「请求长什么样、列表怎么认」这些
 * 与真网关对接时的形状约定；流程行为（重试红线、槽、卡死）由 ChatWireRunnerTest 负责，不重。
 *
 * SSE 剧本一律用 [ok] 的变长参数逐行喂：真协议就是一行一帧，
 * 拼成一行会糊掉解析器（第一版就栽在 raw string 里两帧连排、又没有换行可分）。
 */
class OpenAiCompatClientTest {

    private class FakeTransport(private val responses: List<(WireRequest) -> WireResponse>) : WireTransport {
        var calls = 0
        val seen = mutableListOf<WireRequest>()
        var cancelled = false

        override fun exchange(request: WireRequest, sink: LineSink): WireResponse {
            seen += request
            val make = responses[minOf(calls, responses.lastIndex)]
            calls += 1
            val response = make(request)
            // 200 时把 errorBody 当正文按行喂（列表就是这么读的）；脚本要喂多行就自己拼。
            if (response.status in 200..299) {
                response.errorBody?.lines()?.forEach { line ->
                    if (!sink.onLine(line)) return@forEach
                }
            }
            return response.copy(errorBody = null)
        }

        override fun cancel() { cancelled = true }
        override fun isCancelled(): Boolean = cancelled
    }

    private val profile = ProviderProfile(
        name = "bai2 网关",
        baseUrl = "https://gw.example.com",
        apiKey = "sk-test-key-123",
        model = "qwen3.8-flash",
    )

    private fun clientOf(vararg responses: (WireRequest) -> WireResponse): Pair<OpenAiCompatClient, FakeTransport> {
        val transport = FakeTransport(responses.toList())
        val client = OpenAiCompatClient(
            transport = transport,
            slot = GenerationSlot(),
            policy = RetryPolicy(maxAutomaticRetries = 2, backoffBaseMs = 1L, backoffMaxMs = 2L),
            watchdog = IdleWatchdog(IdleWatchdog.GENERATION_IDLE_MS),
            sleeper = {},
        )
        return client to transport
    }

    /** 200 剧本：每个参数一行（真 SSE 一帧一行），行与行之间用真换行拼。 */
    private fun ok(vararg lines: String) = { _: WireRequest ->
        val payload = lines.joinToString("\n")
        WireResponse(status = 200, retryAfterMs = null, bytesReceived = payload.length.toLong(), errorBody = payload)
    }

    private val doneLine = "data: [DONE]"

    private fun sseContent(text: String) =
        """data: {"choices":[{"delta":{"content":"$text"}}]}"""

    @Test
    fun chatRequestHasOpenAiShapeAndSendsStreamFlag() {
        val (client, transport) = clientOf(
            ok(sseContent("好"), doneLine),
        )

        val err = client.chat(
            profile,
            listOf(ChatTurn("system", "规矩"), ChatTurn("user", "你好")),
        ) { }

        assertNull("正常收场该给 null：$err", err)
        val sent = transport.seen.single()
        assertEquals("https://gw.example.com/v1/chat/completions", sent.url)
        assertEquals("POST", sent.method)
        val body = Json.parseToJsonElement(sent.body!!).jsonObject
        assertEquals("qwen3.8-flash", body.getValue("model").jsonPrimitive.contentOrNull)
        assertTrue("必须显式要流", body.getValue("stream").jsonPrimitive.booleanOrNull == true)
        val messages = body.getValue("messages").jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject.getValue("role").jsonPrimitive.contentOrNull)
        assertEquals("规矩", messages[0].jsonObject.getValue("content").jsonPrimitive.contentOrNull)
        assertEquals("user", messages[1].jsonObject.getValue("role").jsonPrimitive.contentOrNull)
    }

    @Test
    fun baseUrlWithVersionSegmentIsNotPaddedAgain() {
        val (client, transport) = clientOf(ok(sseContent("行"), doneLine))

        val err = client.chat(profile.copy(baseUrl = "https://gw.example.com/compatible-mode/v1"), listOf(ChatTurn("user", "问"))) { }

        assertNull("这条也得真收场：$err", err)
        assertEquals(
            "已带版本段的 base 不许再补一层 v1",
            "https://gw.example.com/compatible-mode/v1/chat/completions",
            transport.seen.single().url,
        )
    }

    @Test
    fun optionalParamsAppearOnlyWhenGiven() {
        val (client, transport) = clientOf(ok(sseContent("行"), doneLine))

        client.chat(profile, listOf(ChatTurn("user", "问")), temperature = 0.3, maxTokens = 128) { }
        val withBoth = Json.parseToJsonElement(transport.seen[0].body!!).jsonObject
        assertEquals(0.3, withBoth.getValue("temperature").jsonPrimitive.doubleOrNull!!, 0.0)
        assertEquals(128, withBoth.getValue("max_tokens").jsonPrimitive.intOrNull)

        client.chat(profile, listOf(ChatTurn("user", "问"))) { }
        val bare = Json.parseToJsonElement(transport.seen[1].body!!).jsonObject
        assertFalse("没给温度就不许塞进去替用户拍默认", bare.containsKey("temperature"))
        assertFalse("max_tokens 同理", bare.containsKey("max_tokens"))
    }

    @Test
    fun authHeaderCarriesBearerOnlyWhenKeyPresent() {
        val (client, transport) = clientOf(ok(sseContent("行"), doneLine))

        client.chat(profile, listOf(ChatTurn("user", "问"))) { }
        val auth = transport.seen[0].headers.first { it.first == "authorization" }.second
        assertEquals("Bearer sk-test-key-123", auth)

        val (client2, transport2) = clientOf(ok(sseContent("行"), doneLine))
        client2.chat(profile.copy(apiKey = ""), listOf(ChatTurn("user", "问"))) { }
        assertTrue(
            "本地端点没密钥就不该发空 Bearer",
            transport2.seen[0].headers.none { it.first == "authorization" },
        )
    }

    @Test
    fun listModelsAcceptsDataArrayShape() {
        // data 形状里只有 name 的条目按 name 兜底收下：解析器一直这么承诺
        // （ollama 的 {"models":[{"name":...}]} 靠同一条兜底），第一版测试注释说反了。
        val payload = """{"data":[{"id":"a-model"},{"id":"b-model"},{"name":"c-model"}]}"""
        val (client, _) = clientOf(ok(payload))

        val listing = client.listModels(profile)

        assertNull(listing.error)
        assertEquals(listOf("a-model", "b-model", "c-model"), listing.models)
    }

    @Test
    fun listModelsAcceptsModelsFieldAndBareArrayShapes() {
        val (clientA, _) = clientOf(ok("""{"models":["x1","x1","x2"]}"""))
        assertEquals("重复 id 要去重", listOf("x1", "x2"), clientA.listModels(profile).models)

        val (clientB, _) = clientOf(ok("""["m-a", "m-b"]"""))
        assertEquals(listOf("m-a", "m-b"), clientB.listModels(profile).models)
    }

    @Test
    fun listModelsOnGibbageSaysEmptyRatherThanInventingNames() {
        val (client, _) = clientOf(ok("not json at all"))

        val listing = client.listModels(profile)

        assertTrue("认不出来就如实给空：${listing.models}", listing.models.isEmpty())
        assertNull("但列表空不等于错：对方 200 我们只是没认出来", listing.error)
    }

    @Test
    fun listModelsRetriesFreeEndpointButChatStaysCostlyGuarded() {
        // 429 一次：列表是免费请求，允许按政策自动再来；这里同时钉第二次真发了
        val (client, transport) = clientOf(
            { WireResponse(429, retryAfterMs = 1L, bytesReceived = 0L, errorBody = """{"error":{"message":"slow"}}""") },
            ok("""{"data":[{"id":"ok-model"}]}"""),
        )

        val listing = client.listModels(profile)

        assertNull(listing.error)
        assertEquals(listOf("ok-model"), listing.models)
        assertEquals(2, transport.calls)
    }

    @Test
    fun chatRefusesLocallyWhenBaseUrlOrHistoryEmpty() {
        val (client, transport) = clientOf(ok(doneLine))

        val noUrl = client.chat(profile.copy(baseUrl = "  "), listOf(ChatTurn("user", "问"))) { }
        assertTrue(noUrl is GenerationError.Configuration)

        val noText = client.chat(profile, listOf(ChatTurn("user", "  "))) { }
        assertTrue("空话不发，不花这个钱", noText is GenerationError.Configuration)
        assertEquals("两次都不许碰网络", 0, transport.calls)
    }

    @Test
    fun listModelsSurfacesProviderErrorShape() {
        val (client, _) = clientOf(
            { WireResponse(401, retryAfterMs = null, bytesReceived = 0L, errorBody = """{"error":{"message":"bad key"}}""") },
        )

        val listing = client.listModels(profile)

        val err = listing.error
        assertTrue("该走供应商分类器：$err", err is GenerationError.Api)
        assertTrue(err!!.userMessage().contains("401"))
    }

    @Test
    fun endpointJoinNeverDoublesSlash() {
        assertEquals("https://x.y/v1/chat/completions", BaseUrlResolver.endpoint("https://x.y/v1/", "/chat/completions"))
        assertEquals("https://x.y", BaseUrlResolver.endpoint("https://x.y/", ""))
    }
}
