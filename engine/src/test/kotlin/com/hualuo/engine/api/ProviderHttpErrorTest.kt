package com.hualuo.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProviderHttpError] 的账：各家错误体形状都要认得，而且**认出来的文本不许带密钥**。
 */
class ProviderHttpErrorTest {

    @Test
    fun openAiStandardShape() {
        val parsed = parseProviderHttpErrorBody(
            """{"error":{"message":"model not found","code":"model_not_found","type":"invalid_request_error"}}""",
        )!!
        assertEquals("model_not_found", parsed.code)
        assertEquals("invalid_request_error", parsed.type)
        assertEquals("model not found", parsed.message)
        assertTrue(parsed.structured)
    }

    @Test
    fun errorAsPlainStringIsStillRead() {
        val parsed = parseProviderHttpErrorBody("""{"error":"上游网关挂了"}""")!!
        assertEquals("上游网关挂了", parsed.message)
        assertTrue("这是从字段取的，算结构化", parsed.structured)
    }

    @Test
    fun otherCommonFieldNamesAreRecognized() {
        assertEquals("细节在此", parseProviderHttpErrorBody("""{"detail":"细节在此"}""")!!.message)
        assertEquals("为啥失败", parseProviderHttpErrorBody("""{"reason":"为啥失败"}""")!!.message)
        assertEquals("授权描述", parseProviderHttpErrorBody("""{"error_description":"授权描述"}""")!!.message)
        assertEquals("顶层兜底", parseProviderHttpErrorBody("""{"error":{"code":"x"},"message":"顶层兜底"}""")!!.message)
    }

    @Test
    fun grpcStyleStatusIsUsedAsType() {
        val parsed = parseProviderHttpErrorBody("""{"error":{"message":"nope","status":"NOT_FOUND"}}""")!!
        assertEquals("NOT_FOUND", parsed.type)
    }

    @Test
    fun htmlAndGarbageBodiesFallBackToRawText() {
        val html = "<html><body>502 Bad Gateway</body></html>"
        val parsed = parseProviderHttpErrorBody(html)!!
        assertFalse("不是 JSON 就别装结构化", parsed.structured)
        assertTrue(parsed.message.contains("502 Bad Gateway"))
        val halfJson = parseProviderHttpErrorBody("""{"error": oops""")!!
        assertFalse(halfJson.structured)
    }

    @Test
    fun emptyAndBlankBodiesGiveNull() {
        assertNull(parseProviderHttpErrorBody(""))
        assertNull(parseProviderHttpErrorBody("   \n "))
    }

    @Test
    fun aPlainJsonStringBodyBecomesTheMessage() {
        val parsed = parseProviderHttpErrorBody("\"额度用完了\"")!!
        assertEquals("额度用完了", parsed.message)
    }

    @Test
    fun missingBodyStillNamesTheStatusCode() {
        val error = providerHttpError(404, null)
        assertTrue("没 body 也要说清是几号：${error.message}", error.message.contains("404"))
        assertTrue(error.userMessage().contains("base URL"))
    }

    @Test
    fun unreadableBodySaysSoInsteadOfShowingNothing() {
        val error = providerHttpError(500, """{"error":{"nested":{"deep":1}}}""")
        assertTrue("读不出形状要讲出来：${error.message}", error.message.contains("形状"))
    }

    @Test
    fun codeFallsBackToHttpStatus() {
        val error = providerHttpError(429, """{"error":{"message":"slow down"}}""")
        assertEquals("429", error.code)
        assertEquals("slow down", error.message)
        assertTrue(error.userMessage().contains("等"))
    }

    @Test
    fun echoedApiKeyInsideProviderMessageReachesUiVerbatim() {
        // 零脱敏纪律：网关把请求上下文原样回显在 message 里——那就原样显示
        val leaky = """{"error":{"message":"bad auth for api_key=sk-abcdef1234567890ABCDEF header Bearer sk-9F8E7D6C5B4A3210FEEDDCC"}}"""
        val parsed = parseProviderHttpErrorBody(leaky)!!
        assertTrue("密钥原文必须在 message 里（零脱敏）：${parsed.message}", parsed.message.contains("sk-abcdef1234567890ABCDEF"))
        assertTrue(parsed.message.contains("sk-9F8E7D6C5B4A3210FEEDDCC"))
        assertTrue("线索在场：${parsed.message}", parsed.message.contains("bad auth"))
        val shown = providerHttpError(401, leaky).userMessage()
        assertTrue("进界面的那句也带密钥原文（零脱敏）：$shown", shown.contains("sk-abcdef1234567890ABCDEF"))
    }

    @Test
    fun structuredErrorMessageOnlyAcceptsStructuredOnes() {
        assertEquals("model not found", structuredErrorMessage("""{"error":{"message":"model not found"}}"""))
        assertNull("HTML 不算结构化 message", structuredErrorMessage("<html>oops</html>"))
    }
}
