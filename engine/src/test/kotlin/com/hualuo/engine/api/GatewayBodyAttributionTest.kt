package com.hualuo.engine.api

import com.hualuo.engine.http.FailureClass
import com.hualuo.engine.http.RequestCost
import com.hualuo.engine.http.RetryDecision
import com.hualuo.engine.http.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关错误体归因的契约（0.7.0 第一刀）：
 *  - 非 2xx 带「上下文超限」证据：决策层 GiveUp(ContextOverflow)（既定行为，这里钉死防回退）；
 *  - HTTP 200 外壳里的流中 {"error":...} 块（不过决策表）：解析器就地归因 + 打码；
 *  - 归因证据 = message + code + type，用未打码原文判（打码插星号会拆关键词）。
 */
class GatewayBodyAttributionTest {

    @Test
    fun gatewayFiftyTwoWrappingOverflowBodyNeverRetriesAndNamesTheWayOut() {
        val decision = RetryPolicy().decide(
            RetryPolicy.AttemptOutcome(
                attempt = 1,
                cost = RequestCost.Costly,
                status = 502,
                body = "502 Bad Gateway: Your input exceeds the context window for this model",
            ),
        )
        assertTrue("超限必须 GiveUp，不许重试", decision is RetryDecision.GiveUp)
        assertEquals(FailureClass.ContextOverflow, (decision as RetryDecision.GiveUp).failure)
    }

    @Test
    fun inStreamErrorBlockWithOverflowEvidenceBecomesContextOverflowTransport() {
        val parser = OpenAiSseParser {}
        val line = "data: {\"error\":{\"message\":\"Your input exceeds the context window for this model\",\"code\":502}}"
        parser.onLine(line)
        val error = parser.streamError
        assertTrue("流中超限必须翻成 Transport(ContextOverflow)", error is GenerationError.Transport)
        assertEquals(FailureClass.ContextOverflow, (error as GenerationError.Transport).failure)
        assertTrue("对方原话要当证据带上", error.detail.contains("context window"))
    }

    @Test
    fun inStreamErrorBlockWithCodeOnlyOverflowIsStillCaught() {
        val parser = OpenAiSseParser {}
        val line = "data: {\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"Too long\"}}"
        parser.onLine(line)
        val error = parser.streamError
        assertTrue("code 里带 context_length_exceeded 也得认出来", error is GenerationError.Transport)
        assertEquals(FailureClass.ContextOverflow, (error as GenerationError.Transport).failure)
    }

    @Test
    fun inStreamErrorBlockCarriesSecretLookingTextVerbatim() {
        // 零脱敏纪律：对方原话一字不改——api_key 原样进消息
        val parser = OpenAiSseParser {}
        val line = "data: {\"error\":{\"message\":\"Invalid credentials api_key=sk-abcdef1234567890abcdef\"}}"
        parser.onLine(line)
        val error = parser.streamError
        assertTrue("普通错误仍是 Api", error is GenerationError.Api)
        val message = (error as GenerationError.Api).message
        assertTrue("密钥原文必须在场（零脱敏）", message.contains("sk-abcdef1234567890abcdef"))
    }

    @Test
    fun plainInStreamErrorKeepsCodeAndType() {
        val parser = OpenAiSseParser {}
        val line = "data: {\"error\":{\"message\":\"Model not found\",\"code\":\"model_not_found\",\"type\":\"invalid_request_error\"}}"
        parser.onLine(line)
        val error = parser.streamError
        assertTrue(error is GenerationError.Api)
        assertEquals("model_not_found", (error as GenerationError.Api).code)
        assertEquals("invalid_request_error", error.type)
    }
}
