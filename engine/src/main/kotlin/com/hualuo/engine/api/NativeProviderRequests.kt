package com.hualuo.engine.api

import com.hualuo.engine.toolcalls.ToolSpec
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal object GeminiRequests : NativeRequests {
    override fun build(session: ProviderSession, history: List<ChatTurn>, tools: List<ToolSpec>): WireRequest {
        val model = session.profile.model.removePrefix("models/")
        val base = BaseUrlResolver.withV1(session.profile.baseUrl)
        val system = history.firstOrNull { it.role == "system" }?.content
        val toolNames = toolNamesById(history)
        val body = buildJsonObject {
            putJsonArray("contents") {
                history.filter { it.role != "system" }.forEach { turn ->
                    addJsonObject {
                        put("role", if (turn.role == "assistant") "model" else "user")
                        putJsonArray("parts") {
                            if (turn.content.isNotEmpty()) addJsonObject { put("text", turn.content) }
                            turn.toolCalls.forEach { call -> addJsonObject { putJsonObject("functionCall") { put("name", call.name); put("arguments", NativeJson.jsonObject(call.argumentsJson)) } } }
                            if (turn.role == "tool") addJsonObject { putJsonObject("functionResponse") { put("name", toolNames[turn.toolCallId].orEmpty()); putJsonObject("response") { put("result", turn.content) } } }
                        }
                    }
                }
            }
            if (!system.isNullOrBlank()) putJsonObject("system_instruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
            if (tools.isNotEmpty()) putJsonArray("tools") { addJsonObject { putJsonArray("function_declarations") { tools.forEach { tool -> addJsonObject { put("name", tool.name); put("description", tool.description); put("parameters", NativeJson.jsonObject(tool.parametersJson)) } } } } }
        }
        return WireRequest(url = "$base/models/$model:streamGenerateContent?alt=sse", method = "POST", headers = listOf("content-type" to "application/json; charset=utf-8", "x-goog-api-key" to session.profile.apiKey), body = body.toString())
    }

    private fun toolNamesById(history: List<ChatTurn>): Map<String, String> = buildMap { history.forEach { turn -> turn.toolCalls.forEach { put(it.id, it.name) } } }
}

internal object AnthropicRequests : NativeRequests {
    override fun build(session: ProviderSession, history: List<ChatTurn>, tools: List<ToolSpec>): WireRequest {
        val system = history.filter { it.role == "system" }.joinToString("\n") { it.content }
        val body = buildJsonObject {
            put("model", session.profile.model); put("max_tokens", 4096); put("stream", true)
            if (system.isNotBlank()) put("system", system)
            putJsonArray("messages") {
                history.filter { it.role != "system" }.forEach { turn ->
                    addJsonObject {
                        put("role", turn.role)
                        putJsonArray("content") {
                            if (turn.content.isNotBlank()) addJsonObject { put("type", "text"); put("text", turn.content) }
                            turn.toolCalls.forEach { call -> addJsonObject { put("type", "tool_use"); put("id", call.id); put("name", call.name); put("input", NativeJson.jsonObject(call.argumentsJson)) } }
                            if (turn.role == "tool") addJsonObject { put("type", "tool_result"); put("tool_use_id", turn.toolCallId.orEmpty()); put("content", turn.content) }
                        }
                    }
                }
            }
            if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { tool -> addJsonObject { put("name", tool.name); put("description", tool.description); put("input_schema", NativeJson.jsonObject(tool.parametersJson)) } } }
        }
        return WireRequest(url = BaseUrlResolver.endpoint(session.profile.baseUrl, "messages"), method = "POST", headers = listOf("content-type" to "application/json; charset=utf-8", "x-api-key" to session.profile.apiKey, "anthropic-version" to "2023-06-01"), body = body.toString())
    }
}

internal object OllamaRequests : NativeRequests {
    override fun build(session: ProviderSession, history: List<ChatTurn>, tools: List<ToolSpec>): WireRequest {
        val body = buildJsonObject {
            put("model", session.profile.model); put("stream", true)
            putJsonArray("messages") { history.forEach { turn -> addJsonObject { put("role", turn.role); put("content", turn.content); if (turn.toolCalls.isNotEmpty()) putJsonArray("tool_calls") { turn.toolCalls.forEach { call -> addJsonObject { putJsonObject("function") { put("name", call.name); put("arguments", NativeJson.jsonObject(call.argumentsJson)) } } } } } } }
            if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { tool -> addJsonObject { putJsonObject("function") { put("name", tool.name); put("description", tool.description); put("parameters", NativeJson.jsonObject(tool.parametersJson)) } } } }
        }
        val headers = mutableListOf("content-type" to "application/json; charset=utf-8")
        if (session.profile.apiKey.isNotBlank()) headers += "authorization" to "Bearer ${session.profile.apiKey}"
        return WireRequest(url = BaseUrlResolver.endpoint(session.profile.baseUrl, "api/chat"), method = "POST", headers = headers, body = body.toString())
    }
}
