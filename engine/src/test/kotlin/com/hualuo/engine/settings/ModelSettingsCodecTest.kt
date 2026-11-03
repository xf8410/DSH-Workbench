package com.hualuo.engine.settings

import com.hualuo.engine.api.ModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSettingsCodecTest {
    @Test fun roundTripKeepsProvidersModelsEnabledAndAliases() {
        val original = ModelSettingsCodec.defaultSettings().copy(providers = ModelSettingsCodec.defaultSettings().providers + ProviderSettings("bai2", true, "https://gw.test/v1", "key"), activeProviderId = "bai2", availableModels = mapOf("bai2" to listOf("bai2:qwen", "bai2:deepseek")), enabledModels = setOf("bai2:qwen"), aliases = mapOf("bai2:qwen" to "快模型"))
        val decoded = ModelSettingsCodec.decode(ModelSettingsCodec.encode(original)); assertEquals("bai2", decoded.activeProviderId); assertEquals("https://gw.test/v1", decoded.provider("bai2")?.baseUrl); assertEquals(listOf("bai2:deepseek", "bai2:qwen"), decoded.availableModels["bai2"]); assertEquals(setOf("bai2:qwen"), decoded.enabledModels); assertEquals("快模型", decoded.aliases["bai2:qwen"])
    }
    @Test fun customModelsRoundTripAndMergeIntoAvailable() {
        val original = ModelSettingsCodec.defaultSettings().copy(customModels = listOf(CustomModel("openai:my-model", "openai", "my-model", "手工模型")))
        val decoded = ModelSettingsCodec.decode(ModelSettingsCodec.encode(original))
        assertEquals(1, decoded.customModels.size); assertEquals("openai:my-model", decoded.customModels.single().id)
        assertTrue("自定义模型要合并进可用清单", decoded.availableModels["openai"].orEmpty().contains("openai:my-model"))
    }
    @Test fun legacyFormatWithoutCustomModelsStillLoads() {
        val decoded = ModelSettingsCodec.decode("{\"format\":1,\"active_provider\":\"openai\",\"providers\":[],\"available_models\":{},\"enabled_models\":[],\"aliases\":{}}")
        assertEquals("openai", decoded.activeProviderId); assertTrue(decoded.customModels.isEmpty())
    }
    @Test fun legacyProviderIsImportedWithoutBeingLost() { val migrated = ModelSettingsCodec.migrateLegacy("旧网关", "https://legacy.test/v1", "legacy-key"); assertEquals("旧网关", migrated.activeProviderId); assertEquals("legacy-key", migrated.provider("旧网关")?.apiKey); assertTrue(migrated.provider("旧网关")?.custom == true) }
    @Test fun modelReferenceKeepsProviderAndBareModel() { val ref = ModelRef.parse("google:models/gemini-2.5-flash"); assertEquals("google", ref.providerId); assertEquals("models/gemini-2.5-flash", ref.model); assertEquals("google:models/gemini-2.5-flash", ref.prefixed) }
    @Test fun badJsonFallsBackToDefaults() { val decoded = ModelSettingsCodec.decode("not json"); assertEquals("openai", decoded.activeProviderId); assertTrue(decoded.providers.isNotEmpty()) }
}
