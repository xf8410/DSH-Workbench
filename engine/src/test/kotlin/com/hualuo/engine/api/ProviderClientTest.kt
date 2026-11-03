package com.hualuo.engine.api

import com.hualuo.engine.generation.GenerationSlot
import com.hualuo.engine.generation.IdleWatchdog
import com.hualuo.engine.toolcalls.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderClientTest {
    private class FakeTransport(private val response: WireResponse) : WireTransport {
        val seen = mutableListOf<WireRequest>(); var cancelled = false
        override fun exchange(request: WireRequest, sink: LineSink): WireResponse { seen += request; response.errorBody?.lines()?.forEach { if (!sink.onLine(it)) return@forEach }; return response.copy(errorBody = null) }
        override fun cancel() { cancelled = true }; override fun isCancelled() = cancelled
    }
    private fun client(transport: WireTransport) = ProviderClient(transport, GenerationSlot(), IdleWatchdog(IdleWatchdog.GENERATION_IDLE_MS))
    private fun session(protocol: ProviderProtocol) = ProviderSession(ProviderProfile(protocol.name, when (protocol) { ProviderProtocol.GEMINI -> "https://google.test/v1beta"; ProviderProtocol.ANTHROPIC -> "https://anthropic.test/v1"; ProviderProtocol.OLLAMA -> "http://localhost:11434"; ProviderProtocol.OPENAI_COMPAT -> "https://openai.test/v1" }, "secret", "model-x"), protocol)

    @Test fun geminiUsesNativeUrlHeadersAndStreamsText() {
        val transport = FakeTransport(WireResponse(200, null, 10L, """data: {"candidates":[{"content":{"parts":[{"text":"好"}]},"finishReason":"STOP"}]}"""))
        val outcome = client(transport).chatTurns(session(ProviderProtocol.GEMINI), listOf(ChatTurn("user", "问"))) { }
        assertTrue(transport.seen.single().url.endsWith("/models/model-x:streamGenerateContent?alt=sse")); assertEquals("secret", transport.seen.single().headers.first { it.first == "x-goog-api-key" }.second); assertTrue(outcome is ChatOutcome.Text)
    }
    @Test fun anthropicAssemblesStreamingToolCall() {
        val lines = listOf("""data: {"type":"content_block_start","content_block":{"type":"tool_use","id":"t1","name":"github_list_repositories"}}""", """data: {"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"{}"}}""", """data: {"type":"content_block_stop"}""", """data: {"type":"message_stop"}""").joinToString("\n")
        val transport = FakeTransport(WireResponse(200, null, 20L, lines)); val outcome = client(transport).chatTurns(session(ProviderProtocol.ANTHROPIC), listOf(ChatTurn("user", "列仓")), listOf(ToolSpec("github_list_repositories", "列仓", "{\"type\":\"object\"}"))) { }
        assertTrue(outcome is ChatOutcome.Calls); assertEquals("github_list_repositories", (outcome as ChatOutcome.Calls).calls.single().name); assertTrue(transport.seen.single().headers.any { it.first == "anthropic-version" })
    }
    @Test fun ollamaUsesTagsAndChatEndpoints() { val transport = FakeTransport(WireResponse(200, null, 20L, """{"models":[{"name":"qwen3:8b"}]}""")); val listing = client(transport).listModels(session(ProviderProtocol.OLLAMA)); assertEquals(listOf("qwen3:8b"), listing.models); assertTrue(transport.seen.single().url.endsWith("/api/tags")) }
    @Test fun incompleteNativeStreamIsNotReportedAsSuccess() { val transport = FakeTransport(WireResponse(200, null, 5L, """data: {"candidates":[{"content":{"parts":[{"text":"半截"}]}}]}""")); val outcome = client(transport).chatTurns(session(ProviderProtocol.GEMINI), listOf(ChatTurn("user", "问"))) { }; assertTrue(outcome is ChatOutcome.Failed); assertTrue((outcome as ChatOutcome.Failed).error is GenerationError.IncompleteStream); assertFalse(transport.cancelled) }
}
