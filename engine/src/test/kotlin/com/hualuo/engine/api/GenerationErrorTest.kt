package com.hualuo.engine.api

import com.hualuo.engine.http.FailureClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GenerationError] 的账：分类必须**各给各的出路**，而且**任何一条都不许把密钥原样吐给人看**。
 * 出路话一律中文（错误码/原话可以带英文，那是线索不是文案）。
 */
class GenerationErrorTest {

    @Test
    fun httpStatusCodesLeadToDifferentNextSteps() {
        val cases = mapOf(
            401 to "密钥",
            403 to "额度",
            404 to "base URL",
            429 to "等",
            500 to "稍后重试",
            413 to "太大",
        )
        val seen = mutableSetOf<String>()
        for ((code, hint) in cases) {
            val message = GenerationError.Network(code, "gateway said no").userMessage()
            assertTrue("$code 的提示要指到「$hint」：$message", message.contains(hint))
            assertTrue("$code 的提示要带状态码：$message", message.contains(code.toString()))
            seen += message
        }
        assertTrue("六条提示不许撞成一句", seen.size == cases.size)
    }

    @Test
    fun unknownStatusStillSaysSomethingUseful() {
        val message = GenerationError.Network(418, "teapot").userMessage()
        assertTrue(message.contains("418"))
        assertTrue(message.contains("teapot"))
    }

    @Test
    fun apiErrorCombinesCodeAndTypeWithoutLeadingJunk() {
        val full = GenerationError.Api("insufficient_quota", "billing_error", "no credits left")
        assertTrue(full.userMessage().startsWith("insufficient_quota billing_error："))
        val bare = GenerationError.Api(null, null, "just text")
        assertTrue(bare.userMessage().contains("just text"))
    }

    @Test
    fun sseParseKeepsAnExcerptInsteadOfSwallowingIt() {
        // 原版这里只剩 "Failed to parse server response."，对方不守规矩时一点线索都没有
        val message = GenerationError.SseParse("<html>502 Bad Gateway</html>", "不是 data: 开头").userMessage()
        assertTrue("要带原文片段：$message", message.contains("502 Bad Gateway"))
        assertTrue("要带原因：$message", message.contains("不是 data: 开头"))
    }

    @Test
    fun incompleteStreamSaysProvablyIncomplete() {
        val plain = GenerationError.IncompleteStream("bai2", null, false, true).userMessage()
        assertTrue(plain.contains("半路断了"))
        assertTrue("已经吐出内容时要提醒别当成品：$plain", plain.contains("别当成品"))
        val withTool = GenerationError.IncompleteStream("bai2", "tool_use", true, false).userMessage()
        assertTrue(withTool.contains("正在写一个工具调用"))
        assertTrue(withTool.contains("tool_use"))
    }

    @Test
    fun truncatedSaysWhichKnobToTurn() {
        val message = GenerationError.OutputTruncated("bai2", "length").userMessage()
        assertTrue(message.contains("最大输出 token"))
        assertTrue(message.contains("思考预算"))
        assertTrue(message.contains("length"))
    }

    @Test
    fun transcriptionNamesItsOwnKind() {
        // 原版把视频/PDF 也报成 "Image transcription failed"
        assertTrue(GenerationError.Transcription("/x/y.pdf", "PDF", "解析不了").userMessage().contains("PDF转写失败"))
        assertTrue(GenerationError.Transcription("/x/y.mp4", "视频", "太长").userMessage().contains("视频转写失败"))
    }

    @Test
    fun configAndCancelledReadLikeHumanSpeech() {
        assertTrue(GenerationError.Configuration("还没填密钥").userMessage().contains("还没填密钥"))
        assertTrue(GenerationError.Cancelled.userMessage().contains("停止"))
        assertTrue(GenerationError.Timeout.userMessage().contains("超时"))
    }

    @Test
    fun unknownExceptionCarriesTheKeyItCarried() {
        // 零脱敏纪律：异常原文进界面一字不改——网关写在查询串上的 api_key 原样显示
        val boom = IllegalStateException(
            "connect failed https://gw.example.com/v1/chat/completions?api_key=sk-abcdef1234567890ABCDEFGH",
        )
        val message = GenerationError.Unknown(boom).userMessage()
        assertTrue("密钥原文必须在场（零脱敏）：$message", message.contains("sk-abcdef1234567890ABCDEFGH"))
        assertTrue("线索保留：$message", message.contains("gw.example.com"))
    }

    @Test
    fun longProviderMessagesGetFoldedAndTruncated() {
        val html = "<html>" + "x".repeat(900) + "</html>"
        val message = GenerationError.Network(500, html).userMessage()
        assertTrue("超长要截断：$message", message.contains("已截断"))
        assertTrue(message.length < 400)
        assertFalse("不许留换行把气泡撑歪", message.contains("\n"))
    }

    @Test
    fun plaintextPassesThroughVerbatim() {
        // 零脱敏纪律（ULTIMATE_RULE ZERO）：错误原文一字不改——密钥串照实显示
        val text = "Authorization: Bearer sk-proj-AAAAAAAAAAAAAAAAAAAA"
        val msg = GenerationError.Network(500, text).userMessage()
        assertTrue("密钥原文必须在场：$msg", msg.contains("sk-proj-AAAAAAAAAAAAAAAAAAAA"))
        assertTrue("Bearer 保留：$msg", msg.contains("Bearer"))

        val labeled = "key=1234567890abcdef1234567890abcdef&model=x"
        val msg2 = GenerationError.Network(500, labeled).userMessage()
        assertTrue("key 值原文必须在场：$msg2", msg2.contains("1234567890abcdef1234567890abcdef"))
    }

    @Test
    fun datedModelNamePassesThroughVerbatim() {
        // 零脱敏后不再有「误伤模型名」的取舍：带日期的模型名原文直通
        val text = "model claude-3-5-sonnet-20240620 not found"
        val msg = GenerationError.Network(500, text).userMessage()
        assertTrue("模型名原文必须在场：$msg", msg.contains("claude-3-5-sonnet-20240620"))
    }

    @Test
    fun gatewayContextOverflowGetsChineseGuidanceAndKeepsEvidence() {
        // 用户实收样本：网关拿 502 包着超限原文。定性在 HttpTaxonomy（body 关键词），
        // 这里钉的是「翻成给人看的那句」的规矩：中文出路 + 对方原话当证据。
        val message = GenerationError.Transport(
            FailureClass.ContextOverflow,
            "502 [upstream_error]: Your input exceeds the context window of this model.",
        ).userMessage()
        assertTrue("出路必须是删历史/开新会话：$message", message.contains("开新会话"))
        assertTrue("不许再教人重试：$message", message.contains("别重发"))
        assertTrue("对方原话是证据，得留下：$message", message.contains("context window"))
    }

    @Test
    fun contextOverflowWithoutEvidenceStillSaysTheWayOut() {
        val message = GenerationError.Transport(FailureClass.ContextOverflow, "").userMessage()
        assertTrue("没原话也要把出路说全：$message", message.contains("开新会话"))
        assertFalse("没证据就不许空引号装样子：$message", message.contains("「」"))
    }
}
