package com.hualuo.engine.api

import com.hualuo.engine.generation.Cancellable
import com.hualuo.engine.generation.GenerationSlot
import com.hualuo.engine.generation.IdleWatchdog
import com.hualuo.engine.http.FailureClass
import com.hualuo.engine.http.RetryPolicy
import com.hualuo.engine.toolcalls.AssembledToolCall
import com.hualuo.engine.toolcalls.ToolSpec
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ModelListing(val models: List<String>, val error: GenerationError?)
data class ProviderSession(val profile: ProviderProfile, val protocol: ProviderProtocol)

class ProviderClient(
    private val transport: WireTransport,
    private val slot: GenerationSlot,
    private val watchdog: IdleWatchdog,
) {
    private val openAi by lazy { OpenAiCompatClient(transport, slot, RetryPolicy(), watchdog) }

    fun chatTurns(session: ProviderSession, history: List<ChatTurn>, tools: List<ToolSpec> = emptyList(), onText: (String) -> Unit): ChatOutcome = when (session.protocol) {
        ProviderProtocol.OPENAI_COMPAT -> openAi.chatTurns(session.profile, history, tools, onText = onText, route = false)
        ProviderProtocol.GEMINI -> runNative(session, history, tools, GeminiRequests, ::GeminiParser, onText)
        ProviderProtocol.ANTHROPIC -> runNative(session, history, tools, AnthropicRequests, ::AnthropicParser, onText)
        ProviderProtocol.OLLAMA -> runNative(session, history, tools, OllamaRequests, ::OllamaParser, onText)
    }

    fun listModels(session: ProviderSession): ModelListing {
        if (session.protocol == ProviderProtocol.OPENAI_COMPAT) {
            val legacy = openAi.listModels(session.profile, route = false)
            return ModelListing(legacy.models, legacy.error)
        }
        val request = when (session.protocol) {
            ProviderProtocol.GEMINI -> WireRequest(url = BaseUrlResolver.endpoint(BaseUrlResolver.withV1(session.profile.baseUrl), "models"), headers = listOf("x-goog-api-key" to session.profile.apiKey))
            ProviderProtocol.ANTHROPIC -> WireRequest(url = BaseUrlResolver.endpoint(session.profile.baseUrl, "models"), headers = listOf("x-api-key" to session.profile.apiKey, "anthropic-version" to "2023-06-01"))
            ProviderProtocol.OLLAMA -> WireRequest(url = BaseUrlResolver.endpoint(session.profile.baseUrl, "api/tags"))
            ProviderProtocol.OPENAI_COMPAT -> error("已在前面返回")
        }
        val body = StringBuilder()
        return try {
            val response = transport.exchange(request) { line -> body.appendLine(line); true }
            if (response.status !in 200..299) ModelListing(emptyList(), providerHttpError(response.status, response.errorBody))
            else {
                val models = NativeModelParser.parse(session.protocol, body.toString())
                if (models.isEmpty()) ModelListing(emptyList(), GenerationError.SseParse(body.toString(), "清单形状无法识别")) else ModelListing(models, null)
            }
        } catch (e: IOException) {
            ModelListing(emptyList(), GenerationError.Transport(FailureClass.NoConnection, e.message.orEmpty()))
        }
    }

    private fun runNative(session: ProviderSession, history: List<ChatTurn>, tools: List<ToolSpec>, requests: NativeRequests, parserFor: ((String) -> Unit) -> NativeStreamParser, onText: (String) -> Unit): ChatOutcome {
        if (session.profile.baseUrl.isBlank()) return ChatOutcome.Failed(GenerationError.Configuration("「${session.profile.name}」没填 base URL"))
        val parser = parserFor(onText)
        val claim = slot.tryBegin(Cancellable { transport.cancel() }) ?: return ChatOutcome.Failed(GenerationError.Configuration("上一条还在生成，槽被占着：这条没发出去。等它收完或先按停止"))
        return try {
            val response = transport.exchange(requests.build(session, history, tools)) { line -> watchdog.beat(); parser.onLine(line) }
            when {
                response.status !in 200..299 -> ChatOutcome.Failed(providerHttpError(response.status, response.errorBody))
                transport.isCancelled() -> ChatOutcome.Failed(GenerationError.Cancelled)
                !parser.finished -> ChatOutcome.Failed(GenerationError.IncompleteStream(session.profile.name, parser.finishReason, parser.sawToolCallFrames, parser.sawText))
                parser.toolCalls.isNotEmpty() -> ChatOutcome.Calls(parser.toolCalls)
                else -> ChatOutcome.Text
            }
        } catch (e: IOException) {
            val failure = when { transport.isCancelled() -> FailureClass.Cancelled; e is SocketTimeoutException || slot.stalled() -> FailureClass.Stalled; else -> FailureClass.NoConnection }
            ChatOutcome.Failed(GenerationError.Transport(failure, e.message.orEmpty()))
        } finally { slot.end(claim) }
    }
}

internal interface NativeRequests { fun build(session: ProviderSession, history: List<ChatTurn>, tools: List<ToolSpec>): WireRequest }
internal interface NativeStreamParser {
    var finished: Boolean
    var finishReason: String?
    var sawText: Boolean
    var sawToolCallFrames: Boolean
    val toolCalls: List<AssembledToolCall>
    fun onLine(line: String): Boolean
}
internal object NativeJson {
    val json = Json { ignoreUnknownKeys = true }
    fun jsonObject(raw: String): JsonObject = runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrElse { JsonObject(emptyMap()) }
    fun array(value: kotlinx.serialization.json.JsonElement?): JsonArray = value as? JsonArray ?: JsonArray(emptyList())
    fun primitive(value: kotlinx.serialization.json.JsonElement?): String? = (value as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
