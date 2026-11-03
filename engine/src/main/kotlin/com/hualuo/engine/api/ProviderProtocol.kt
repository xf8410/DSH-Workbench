package com.hualuo.engine.api

enum class ProviderProtocol {
    OPENAI_COMPAT,
    GEMINI,
    ANTHROPIC,
    OLLAMA,
}

data class ProviderDefinition(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val protocol: ProviderProtocol,
    val keyRequired: Boolean,
)

object ProviderCatalog {
    val builtIns: List<ProviderDefinition> = listOf(
        ProviderDefinition("google", "Google", "https://generativelanguage.googleapis.com/v1beta", ProviderProtocol.GEMINI, true),
        ProviderDefinition("openai", "OpenAI", "https://api.openai.com/v1", ProviderProtocol.OPENAI_COMPAT, true),
        ProviderDefinition("anthropic", "Anthropic", "https://api.anthropic.com/v1", ProviderProtocol.ANTHROPIC, true),
        ProviderDefinition("deepseek", "DeepSeek", "https://api.deepseek.com/v1", ProviderProtocol.OPENAI_COMPAT, true),
        ProviderDefinition("qwen", "Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", ProviderProtocol.OPENAI_COMPAT, true),
        ProviderDefinition("groq", "Groq", "https://api.groq.com/openai/v1", ProviderProtocol.OPENAI_COMPAT, true),
        ProviderDefinition("ollama", "Ollama", "http://localhost:11434", ProviderProtocol.OLLAMA, false),
        ProviderDefinition("openrouter", "Open Router", "https://openrouter.ai/api/v1", ProviderProtocol.OPENAI_COMPAT, true),
    )

    fun byId(id: String): ProviderDefinition? = builtIns.firstOrNull { it.id == id }
}

data class ModelRef(val providerId: String, val model: String) {
    val prefixed: String get() = "$providerId:$model"

    companion object {
        fun parse(raw: String): ModelRef {
            val value = raw.trim()
            val index = value.indexOf(':')
            return if (index > 0 && index < value.lastIndex) ModelRef(value.substring(0, index), value.substring(index + 1))
            else ModelRef("", value.removePrefix("models/"))
        }
    }
}
