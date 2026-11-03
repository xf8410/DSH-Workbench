package com.hualuo.engine.vision

import com.hualuo.engine.api.LineSink
import com.hualuo.engine.api.ProviderProtocol
import com.hualuo.engine.api.ProviderProfile
import com.hualuo.engine.api.ProviderSession
import com.hualuo.engine.api.WireRequest
import com.hualuo.engine.api.WireResponse
import com.hualuo.engine.api.WireStreamIOException
import com.hualuo.engine.api.WireTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视觉三件对照表（全离线，假 transport 喂脚本）。
 */
class VisionTurnsTest {

    private fun session(protocol: ProviderProtocol) = ProviderSession(
        ProviderProfile("测试", "https://api.example.com/v1", "sk-test", "vision-model"),
        protocol,
    )

    private val b64 = "aGVsbG8=" // "hello"

    // ---------- 构造 ----------

    @Test
    fun openAiBodyShape() {
        val req = VisionTurns.build(session(ProviderProtocol.OPENAI_COMPAT), listOf(b64), "看到了什么")!!
        assertTrue(req.url.endsWith("/chat/completions"))
        assertTrue(req.headers.any { it.first == "authorization" && it.second == "Bearer sk-test" })
        assertTrue(req.body!!.contains("\"type\":\"image_url\""))
        assertTrue(req.body!!.contains("data:image/jpeg;base64,$b64"))
        assertTrue(req.body!!.contains("看到了什么"))
    }

    @Test
    fun geminiBodyShape() {
        val req = VisionTurns.build(session(ProviderProtocol.GEMINI), listOf(b64), "读文字")!!
        assertTrue(req.url.contains("/models/vision-model:generateContent"))
        assertTrue(req.headers.any { it.first == "x-goog-api-key" && it.second == "sk-test" })
        assertTrue(req.body!!.contains("\"inline_data\""))
        assertTrue(req.body!!.contains("\"mime_type\":\"image/jpeg\""))
        assertTrue(req.body!!.contains(b64))
    }

    @Test
    fun anthropicBodyShape() {
        val req = VisionTurns.build(session(ProviderProtocol.ANTHROPIC), listOf(b64), "描述")!!
        assertTrue(req.url.endsWith("/messages"))
        assertTrue(req.headers.any { it.first == "x-api-key" && it.second == "sk-test" })
        assertTrue(req.body!!.contains("\"type\":\"image\""))
        assertTrue(req.body!!.contains("\"media_type\":\"image/jpeg\""))
    }

    @Test
    fun ollamaRefusesVision() {
        assertEquals(null, VisionTurns.build(session(ProviderProtocol.OLLAMA), listOf(b64), "x"))
    }

    @Test
    fun emptyImagesBuildsNull() {
        assertEquals(null, VisionTurns.build(session(ProviderProtocol.OPENAI_COMPAT), emptyList(), "x"))
    }

    // ---------- 解析 ----------

    @Test
    fun extractTextThreeShapes() {
        assertEquals("答案A", VisionTurns.extractText(
            ProviderProtocol.OPENAI_COMPAT,
            """{"choices":[{"message":{"content":"答案A"}}]}""",
        ))
        assertEquals("答案B", VisionTurns.extractText(
            ProviderProtocol.GEMINI,
            """{"candidates":[{"content":{"parts":[{"text":"答案"},{"text":"B"}]}}]}""",
        ))
        assertEquals("答案C", VisionTurns.extractText(
            ProviderProtocol.ANTHROPIC,
            """{"content":[{"type":"text","text":"答案C"}]}""",
        ))
    }

    @Test
    fun extractTextGarbageIsEmpty() {
        assertEquals("", VisionTurns.extractText(ProviderProtocol.OPENAI_COMPAT, "<html>网关错误</html>"))
        assertEquals("", VisionTurns.extractText(ProviderProtocol.OPENAI_COMPAT, "{}"))
    }

    @Test
    fun extractErrorMessage() {
        assertEquals("模型不存在", VisionTurns.extractError(
            """{"error":{"message":"模型不存在","type":"invalid_request_error"}}""",
        ))
        assertEquals(null, VisionTurns.extractError("<html>502</html>"))
    }

    // ---------- VideoPlan ----------

    @Test
    fun frameTimesEvenlySpread() {
        val times = VideoPlan.frameTimes(60_000L, 10)
        assertEquals(10, times.size)
        assertTrue("头尾让 5%：${times.first()}", times.first() >= 3_000L)
        assertTrue(times.last() <= 57_000L)
        // 单调且均匀（相邻差一致）
        val diffs = times.zipWithNext { a, b -> b - a }.distinct()
        assertTrue("相邻差只有一种：$diffs", diffs.size <= 2)
    }

    @Test
    fun frameTimesShortVideo() {
        assertEquals(listOf(500L), VideoPlan.frameTimes(1_000L, 10))
        assertEquals(listOf(0L), VideoPlan.frameTimes(0L, 10))
    }

    @Test
    fun batchesChunkByFour() {
        val times = VideoPlan.frameTimes(120_000L, 10)
        val batches = VideoPlan.batches(times)
        assertEquals(3, batches.size)
        assertEquals(listOf(4, 4, 2), batches.map { it.size })
    }

    @Test
    fun promptsCarryContextAndHonestyRules() {
        val p = VideoPlan.describePrompt(0, 3, listOf(3000L, 9000L, 12000L, 15000L))
        assertTrue(p.contains("第 1/3 批"))
        assertTrue(p.contains("3.0s"))
        assertTrue(p.contains("不许猜"))
        val s = VideoPlan.summarizePrompt("批一描述\n批二描述", "上克馘打法")
        assertTrue(s.contains("【画面流水】"))
        assertTrue(s.contains("【攻略要点】"))
        assertTrue(s.contains("上克馘打法"))
    }

    // ---------- VisionExec（假 transport） ----------

    private fun fakeTransport(status: Int, body: String) = object : WireTransport {
        var lastRequest: WireRequest? = null
        override fun exchange(request: WireRequest, sink: LineSink): WireResponse {
            lastRequest = request
            sink.onLine(body)
            return if (status in 200..299) WireResponse(status, null, body.length.toLong(), null)
            else WireResponse(status, null, 0, body)
        }
        override fun cancel() {}
        override fun isCancelled(): Boolean = false
    }

    @Test
    fun execOkPath() {
        val t = fakeTransport(200, """{"choices":[{"message":{"content":"三局两胜"}}]}""")
        val out = VisionExec.ask(
            session(ProviderProtocol.OPENAI_COMPAT), t, listOf(b64), "谁赢了",
        )
        assertTrue(out is VisionExec.Outcome.Ok && out.text == "三局两胜")
        assertEquals("Bearer sk-test", t.lastRequest!!.headers.first { it.first == "authorization" }.second)
    }

    @Test
    fun execErrorPathCarriesDetail() {
        val t = fakeTransport(404, """{"error":{"message":"model not found"}}""")
        val out = VisionExec.ask(session(ProviderProtocol.OPENAI_COMPAT), t, listOf(b64), "x")
        assertTrue(out is VisionExec.Outcome.Failed && out.reason.contains("model not found"))
    }

    @Test
    fun execOllamaFailsCleanly() {
        val t = fakeTransport(200, "{}")
        val out = VisionExec.ask(session(ProviderProtocol.OLLAMA), t, listOf(b64), "x")
        assertTrue(out is VisionExec.Outcome.Failed && out.reason.contains("没接视觉"))
    }

    @Test
    fun execTextSummaryHasNoImage() {
        val t = fakeTransport(200, """{"choices":[{"message":{"content":"汇总好了"}}]}""")
        val out = VisionExec.askText(session(ProviderProtocol.OPENAI_COMPAT), t, "把描述汇总")
        assertTrue(out is VisionExec.Outcome.Ok)
        assertTrue("汇总请求不带图：${t.lastRequest!!.body}", !t.lastRequest!!.body!!.contains("image_url"))
        assertTrue(t.lastRequest!!.body!!.contains("把描述汇总"))
    }
}
