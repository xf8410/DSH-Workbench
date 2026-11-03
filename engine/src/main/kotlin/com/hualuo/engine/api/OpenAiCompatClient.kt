package com.hualuo.engine.api

import com.hualuo.engine.generation.GenerationSlot
import com.hualuo.engine.generation.IdleWatchdog
import com.hualuo.engine.http.FailureClass
import com.hualuo.engine.http.RequestCost
import com.hualuo.engine.http.RetryDecision
import com.hualuo.engine.http.RetryPolicy
import com.hualuo.engine.toolcalls.AssembledToolCall
import com.hualuo.engine.toolcalls.ToolSpec
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class ProviderProfile(val name: String, val baseUrl: String, val apiKey: String, val model: String)
data class WireToolCall(val id: String, val name: String, val argumentsJson: String)
data class ChatTurn(val role: String, val content: String, val toolCalls: List<WireToolCall> = emptyList(), val toolCallId: String? = null)
sealed class ChatOutcome {
    object Text : ChatOutcome()
    data class Calls(val calls: List<AssembledToolCall>) : ChatOutcome()
    data class Failed(val error: GenerationError) : ChatOutcome()
}

class OpenAiCompatClient(
    private val transport: WireTransport,
    private val slot: GenerationSlot,
    private val policy: RetryPolicy,
    private val watchdog: IdleWatchdog,
    private val sleeper: (Long) -> Unit = ::napQuietly,
) {
    // route 放在 onText 前，保证旧的 chatTurns(..., tools) { chunk -> } 尾随 lambda 仍绑定 onText。
    fun chatTurns(profile: ProviderProfile, history: List<ChatTurn>, tools: List<ToolSpec> = emptyList(), temperature: Double? = null, maxTokens: Int? = null, route: Boolean = true, onText: (String) -> Unit): ChatOutcome {
        val routed = if (route) ProviderRouting.sessionFor(profile.model) else null
        if (routed != null && routed.protocol != ProviderProtocol.OPENAI_COMPAT) return ProviderClient(transport, slot, watchdog).chatTurns(routed, history, tools, onText)
        val active = routed?.profile ?: profile
        if (active.baseUrl.isBlank()) return ChatOutcome.Failed(GenerationError.Configuration("「${active.name}」没填 base URL，没东西可发"))
        if (history.none { it.content.isNotBlank() || it.toolCalls.isNotEmpty() || it.role == "tool" }) return ChatOutcome.Failed(GenerationError.Configuration("这条对话是空的，不花这个钱"))
        val request = WireRequest(url = BaseUrlResolver.endpoint(BaseUrlResolver.withV1(active.baseUrl), CHAT_SUFFIX), method = "POST", headers = headersFor(active, true), body = chatBody(active, history, temperature, maxTokens, tools))
        val runner = ChatWireRunner(transport, slot, policy, watchdog, providerLabel = active.name, sleeper = sleeper)
        return when (val result = runner.run(request, RequestCost.Costly, onText)) {
            is ChatRunResult.Ok -> ChatOutcome.Text
            is ChatRunResult.ToolCalls -> ChatOutcome.Calls(result.calls)
            is ChatRunResult.Failed -> ChatOutcome.Failed(result.error)
        }
    }

    fun chat(profile: ProviderProfile, history: List<ChatTurn>, temperature: Double? = null, maxTokens: Int? = null, onText: (String) -> Unit): GenerationError? = when (val outcome = chatTurns(profile, history, emptyList(), temperature, maxTokens, onText = onText)) {
        ChatOutcome.Text -> null
        is ChatOutcome.Calls -> GenerationError.Configuration("模型回了工具调用，但这轮请求没带工具清单（形状异常）：按错误报，别装没看见")
        is ChatOutcome.Failed -> outcome.error
    }

    data class ModelListing(val models: List<String>, val error: GenerationError?)

    fun listModels(profile: ProviderProfile, route: Boolean = true): ModelListing {
        val routed = if (route) ProviderRouting.sessionFor(profile.model) else null
        if (routed != null && routed.protocol != ProviderProtocol.OPENAI_COMPAT) return ProviderClient(transport, slot, watchdog).listModels(routed).let { ModelListing(it.models, it.error) }
        val active = routed?.profile ?: profile
        if (active.baseUrl.isBlank()) return ModelListing(emptyList(), GenerationError.Configuration("「${active.name}」没填 base URL"))
        val request = WireRequest(url = BaseUrlResolver.endpoint(BaseUrlResolver.withV1(active.baseUrl), MODELS_SUFFIX), method = "GET", headers = headersFor(active, false))
        var attempt = 1
        while (true) {
            val body = StringBuilder()
            val outcome = try {
                val response = transport.exchange(request) { line -> body.appendLine(line); true }
                if (response.status in 200..299) return ModelListing(parseModelIds(body.toString()), null)
                RetryPolicy.AttemptOutcome(attempt, RequestCost.Free, response.status, body = response.errorBody ?: "", retryAfterMs = response.retryAfterMs)
            } catch (e: IOException) {
                RetryPolicy.AttemptOutcome(attempt, RequestCost.Free, status = 0, localFailure = if (transport.isCancelled()) FailureClass.Cancelled else FailureClass.NoConnection)
            }
            when (val decision = policy.decide(outcome)) {
                is RetryDecision.Retry -> { attempt = decision.attempt; sleeper(decision.waitMs) }
                is RetryDecision.GiveUp -> return ModelListing(emptyList(), if (decision.failure == FailureClass.Cancelled) GenerationError.Cancelled else if (outcome.status >= 400) providerHttpError(outcome.status, outcome.body) else GenerationError.Transport(decision.failure, decision.reason))
                RetryDecision.Done -> return ModelListing(emptyList(), GenerationError.Transport(FailureClass.Unknown, "列表的收场没被正确接住"))
            }
        }
    }

    private fun headersFor(profile: ProviderProfile, acceptSse: Boolean): List<Pair<String, String>> = buildList { add("content-type" to "application/json; charset=utf-8"); if (acceptSse) add("accept" to "text/event-stream"); if (profile.apiKey.isNotBlank()) add("authorization" to "Bearer ${profile.apiKey}") }

    private fun chatBody(profile: ProviderProfile, history: List<ChatTurn>, temperature: Double?, maxTokens: Int?, tools: List<ToolSpec>): String = buildJsonObject {
        put("model", profile.model); put("stream", true); temperature?.let { put("temperature", it) }; maxTokens?.let { put("max_tokens", it) }
        putJsonArray("messages") { history.forEach { turn -> addJsonObject {
            put("role", turn.role)
            if (turn.role == "tool") { put("tool_call_id", turn.toolCallId ?: ""); put("content", turn.content) }
            else if (turn.toolCalls.isNotEmpty()) { put("content", turn.content); putJsonArray("tool_calls") { turn.toolCalls.forEach { call -> addJsonObject { put("id", call.id); put("type", "function"); putJsonObject("function") { put("name", call.name); put("arguments", call.argumentsJson) } } } } }
            else put("content", turn.content)
        } } }
        if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { spec -> addJsonObject { put("type", "function"); putJsonObject("function") { put("name", spec.name); put("description", spec.description); put("parameters", parseSchemaOrEmpty(spec.parametersJson)) } } } }
    }.toString()

    private fun parseSchemaOrEmpty(schemaJson: String): JsonObject = runCatching { json.parseToJsonElement(schemaJson) }.getOrNull() as? JsonObject ?: buildJsonObject { put("type", "object"); putJsonObject("properties") {} }

    private companion object {
        const val CHAT_SUFFIX = "chat/completions"; const val MODELS_SUFFIX = "models"; val json = Json { ignoreUnknownKeys = true }
        fun parseModelIds(text: String): List<String> {
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
            val array: JsonArray = when (root) { is JsonArray -> root; is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: return emptyList(); else -> return emptyList() }
            return array.mapNotNull { element -> when (element) { is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }; is JsonObject -> ((element["id"] as? JsonPrimitive)?.contentOrNull ?: (element["name"] as? JsonPrimitive)?.contentOrNull)?.trim()?.takeIf { it.isNotEmpty() }; else -> null } }.distinct()
        }
    }
}

private fun napQuietly(ms: Long) { if (ms <= 0L) return; try { Thread.sleep(ms) } catch (e: InterruptedException) { Thread.currentThread().interrupt() } }
