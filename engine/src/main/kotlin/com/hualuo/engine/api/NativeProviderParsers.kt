package com.hualuo.engine.api

import com.hualuo.engine.toolcalls.AssembledToolCall
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class GeminiParser(private val onText: (String) -> Unit) : NativeStreamParser {
    override var finished = false
    override var finishReason: String? = null
    override var sawText = false
    override var sawToolCallFrames = false
    private val calls = mutableListOf<AssembledToolCall>()
    override val toolCalls: List<AssembledToolCall> get() = calls.toList()

    override fun onLine(line: String): Boolean {
        val payload = line.trim().removePrefix("data:").trim()
        if (payload == "[DONE]") { finished = true; return false }
        val root = runCatching { NativeJson.json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return true
        val candidate = NativeJson.array(root["candidates"]).firstOrNull() as? JsonObject
        val content = candidate?.get("content") as? JsonObject
        NativeJson.array(content?.get("parts")).forEach { part ->
            val obj = part as? JsonObject ?: return@forEach
            val text = (obj["text"] as? JsonPrimitive)?.contentOrNull
            if (!text.isNullOrEmpty()) { sawText = true; onText(text) }
            val call = obj["functionCall"] as? JsonObject
            if (call != null) {
                sawToolCallFrames = true
                calls += AssembledToolCall("gemini_${UUID.randomUUID()}", NativeJson.primitive(call["name"]).orEmpty(), (call["arguments"] as? JsonObject)?.toString() ?: "{}")
            }
        }
        val reason = NativeJson.primitive(candidate?.get("finishReason"))
        if (!reason.isNullOrBlank()) { finishReason = reason; finished = true }
        return !finished
    }
}

internal class AnthropicParser(private val onText: (String) -> Unit) : NativeStreamParser {
    override var finished = false
    override var finishReason: String? = null
    override var sawText = false
    override var sawToolCallFrames = false
    private val calls = mutableListOf<AssembledToolCall>()
    override val toolCalls: List<AssembledToolCall> get() = calls.toList()
    private var callId = ""
    private var callName = ""
    private val callArgs = StringBuilder()

    override fun onLine(line: String): Boolean {
        val payload = line.trim().removePrefix("data:").trim()
        val root = runCatching { NativeJson.json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return true
        when (NativeJson.primitive(root["type"])) {
            "content_block_start" -> {
                val block = root["content_block"] as? JsonObject
                if (NativeJson.primitive(block?.get("type")) == "tool_use") {
                    sawToolCallFrames = true; callId = NativeJson.primitive(block?.get("id")).orEmpty(); callName = NativeJson.primitive(block?.get("name")).orEmpty(); callArgs.clear()
                }
            }
            "content_block_delta" -> {
                val delta = root["delta"] as? JsonObject
                val text = (delta?.get("text") as? JsonPrimitive)?.contentOrNull
                if (!text.isNullOrEmpty()) { sawText = true; onText(text) }
                callArgs.append((delta?.get("partial_json") as? JsonPrimitive)?.contentOrNull.orEmpty())
            }
            "content_block_stop" -> flushCall()
            "message_delta" -> finishReason = NativeJson.primitive((root["delta"] as? JsonObject)?.get("stop_reason"))
            "message_stop" -> { flushCall(); finished = true }
        }
        return !finished
    }

    private fun flushCall() {
        if (callName.isBlank()) return
        calls += AssembledToolCall(callId.ifBlank { "anthropic_${UUID.randomUUID()}" }, callName, callArgs.toString().ifBlank { "{}" })
        callId = ""; callName = ""; callArgs.clear()
    }
}

internal class OllamaParser(private val onText: (String) -> Unit) : NativeStreamParser {
    override var finished = false
    override var finishReason: String? = null
    override var sawText = false
    override var sawToolCallFrames = false
    private val calls = mutableListOf<AssembledToolCall>()
    override val toolCalls: List<AssembledToolCall> get() = calls.toList()

    override fun onLine(line: String): Boolean {
        val root = runCatching { NativeJson.json.parseToJsonElement(line.trim()) }.getOrNull() as? JsonObject ?: return true
        val message = root["message"] as? JsonObject
        val text = (message?.get("content") as? JsonPrimitive)?.contentOrNull
        if (!text.isNullOrEmpty()) { sawText = true; onText(text) }
        NativeJson.array(message?.get("tool_calls")).forEach { element ->
            val function = (element as? JsonObject)?.get("function") as? JsonObject ?: return@forEach
            val name = NativeJson.primitive(function["name"]).orEmpty()
            if (name.isNotBlank()) {
                sawToolCallFrames = true
                calls += AssembledToolCall("ollama_${UUID.randomUUID()}", name, (function["arguments"] as? JsonObject)?.toString() ?: "{}")
            }
        }
        if ((root["done"] as? JsonPrimitive)?.contentOrNull == "true") { finishReason = "stop"; finished = true }
        return !finished
    }
}

internal object NativeModelParser {
    fun parse(protocol: ProviderProtocol, raw: String): List<String> {
        val root = runCatching { NativeJson.json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return emptyList()
        val array = when (protocol) {
            ProviderProtocol.GEMINI -> NativeJson.array(root["models"])
            ProviderProtocol.ANTHROPIC -> NativeJson.array(root["data"])
            ProviderProtocol.OLLAMA -> NativeJson.array(root["models"])
            ProviderProtocol.OPENAI_COMPAT -> JsonArray(emptyList())
        }
        return array.mapNotNull { item ->
            val obj = item as? JsonObject
            val methods = NativeJson.array(obj?.get("supportedGenerationMethods")).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (protocol == ProviderProtocol.GEMINI && methods.isNotEmpty() && "generateContent" !in methods) return@mapNotNull null
            NativeJson.primitive(obj?.get("id")) ?: NativeJson.primitive(obj?.get("name")) ?: (item as? JsonPrimitive)?.contentOrNull
        }.map { it.removePrefix("models/") }.filter { it.isNotBlank() }.distinct().sorted()
    }
}
