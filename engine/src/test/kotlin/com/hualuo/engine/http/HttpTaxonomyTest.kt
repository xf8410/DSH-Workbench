package com.hualuo.engine.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败分类测试：前四条是从旧仓 `HttpGenerationErrorPolicyTest` 原样搬来的（它们在真供应商
 * 响应上被验证过），后面几条钉的是旧仓漏掉的东西 —— Cloudflare 那一家子状态码，
 * 以及「不许悄悄改正文」的截断规矩。
 *
 * 样本必须留在能力圈内：这套正则**全靠分隔符**（空格或下划线）认词，紧凑驼峰认不出；
 * 能力边界由 [compactCamelCaseIsBeyondThisRuleSet] 钉住，将来谁扩展规则，那条会红着提醒同步。
 */
class HttpTaxonomyTest {

    @Test
    fun plain502GatewayFailureIsTransientAndNotContextOverflow() {
        val body = "upstream connect error or disconnect reset before headers"
        assertFalse(HttpTaxonomy.isContextOverflow(body))
        assertEquals(FailureClass.Gateway, HttpTaxonomy.classify(502, body))
        assertTrue(HttpTaxonomy.isTransient(502))
    }

    @Test
    fun status502WithProviderContextEvidenceIsClassifiedFromBody() {
        val body = "maximum context length is 131072 tokens; your request has 140000 tokens"
        assertTrue(HttpTaxonomy.isContextOverflow(body))
        assertEquals(FailureClass.ContextOverflow, HttpTaxonomy.classify(502, body))
    }

    @Test
    fun ordinary400ContextResponseDoesNotNeedStatus502() {
        val body = "input is too long for the model token limit"
        assertTrue(HttpTaxonomy.isContextOverflow(body))
        assertEquals(FailureClass.ContextOverflow, HttpTaxonomy.classify(400, body))
    }

    @Test
    fun unrelatedTokenWordingIsNotContextOverflow() {
        val body = "invalid authentication token"
        assertFalse(HttpTaxonomy.isContextOverflow(body))
        assertEquals(FailureClass.Client, HttpTaxonomy.classify(401, body))
    }

    @Test
    fun cloudflareFamilyIsAllTransient() {
        // 旧仓白名单 {429,502,503,504} 漏掉的整家：这里逐个钉住，改名/删一个都会红
        for (status in listOf(520, 521, 522, 523, 524, 525, 526, 598, 599)) {
            assertTrue("$status 该算瞬时失败", HttpTaxonomy.isTransient(status))
        }
    }

    @Test
    fun gatewayTimeoutCodesGetTheirOwnClass() {
        assertEquals(FailureClass.GatewayTimeout, HttpTaxonomy.classify(524, "<html>time out</html>"))
        assertEquals(FailureClass.GatewayTimeout, HttpTaxonomy.classify(504, ""))
        assertEquals(FailureClass.GatewayTimeout, HttpTaxonomy.classify(408, ""))
    }

    @Test
    fun rateLimitedStaysItsOwnClass() {
        assertEquals(FailureClass.RateLimited, HttpTaxonomy.classify(429, "slow down"))
        assertTrue(HttpTaxonomy.isTransient(429))
    }

    @Test
    fun serverBugCodesAreOnlyForIdempotentRequests() {
        for (status in listOf(500, 501, 507, 509)) {
            assertTrue("$status 该在仅幂等表里", HttpTaxonomy.isIdempotentOnly(status))
            assertFalse("$status 不该进自动重来表", HttpTaxonomy.isTransient(status))
            assertEquals(FailureClass.Gateway, HttpTaxonomy.classify(status, "boom"))
        }
    }

    @Test
    fun separatedSpellingsOfOverflowAllMatch() {
        val samples = listOf(
            "context_length_exceeded",
            "context window exceeded",
            "context window overflow",
            "prompt is too long",
            "input token count exceeds the maximum",
            "requested token budget exceeded",
            "too many input tokens",
            "max_tokens exceed",
            "request_too_large",
            "exceeds the maximum number of input tokens",
        )
        for (sample in samples) {
            assertTrue("该认出超限：$sample", HttpTaxonomy.isContextOverflow(sample))
        }
    }

    @Test
    fun compactCamelCaseIsBeyondThisRuleSet() {
        assertFalse(HttpTaxonomy.isContextOverflow("ContextWindowTooLarge"))
        assertFalse(HttpTaxonomy.isContextOverflow("RequestTooLarge"))
    }

    @Test
    fun wordingWithoutOverflowMeaningIsNotMatched() {
        // 「over the maximum」这种没有 exceed/greater/more 锚点的说法，规则认不出：
        // 钉住它，免得有人以为已经覆盖了所有英文表述
        assertFalse(HttpTaxonomy.isContextOverflow("your prompt is over the limit"))
    }

    @Test
    fun blankBodyIsNeverAnOverflow() {
        assertFalse(HttpTaxonomy.isContextOverflow(""))
        assertFalse(HttpTaxonomy.isContextOverflow("   \n  "))
    }

    @Test
    fun otherFourXxAreClientErrors() {
        assertEquals(FailureClass.Client, HttpTaxonomy.classify(404, "model not found"))
        assertEquals(FailureClass.Client, HttpTaxonomy.classify(403, "forbidden"))
        assertEquals(FailureClass.Client, HttpTaxonomy.classify(422, "unprocessable"))
    }

    @Test
    fun unknownCodesFallToUnknown() {
        assertEquals(FailureClass.Unknown, HttpTaxonomy.classify(101, ""))
        assertEquals(FailureClass.Unknown, HttpTaxonomy.classify(0, ""))
    }

    @Test
    fun truncateOnlyCutsTheTailAndAddsNothing() {
        val long = "x".repeat(HttpTaxonomy.MAX_PROVIDER_MESSAGE_CHARS + 500)

        val cut = HttpTaxonomy.truncateProviderMessage(long)

        assertEquals(HttpTaxonomy.MAX_PROVIDER_MESSAGE_CHARS, cut.length)
        assertTrue("必须是原文本的前缀（不许塞截断标记）", long.startsWith(cut))
        assertFalse("不许往正文里加字", cut.contains("…") || cut.contains("截断"))
    }

    @Test
    fun shortMessagePassesThroughUntouched() {
        val body = "Bad Gateway"
        assertEquals(body, HttpTaxonomy.truncateProviderMessage(body))
    }

    @Test
    fun chineseProviderWordingNeedsTheEnglishAnchor() {
        // 国产网关常中英混排：英文锚点在就能认；纯中文目前认不出，这是已知边界。
        assertTrue(HttpTaxonomy.isContextOverflow("上下文长度已超出 maximum context length"))
        assertFalse(HttpTaxonomy.isContextOverflow("上下文已保存"))
        assertFalse("纯中文超限暂不认（要认就得同步补规则与测试）", HttpTaxonomy.isContextOverflow("上下文长度超出上限"))
    }
}
