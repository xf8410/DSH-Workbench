package com.hualuo.engine.api

import com.hualuo.engine.generation.GenerationSlot
import com.hualuo.engine.generation.IdleWatchdog
import com.hualuo.engine.http.RequestCost
import com.hualuo.engine.http.RetryPolicy
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接线层的脚本化端到端测试：假 transport 按剧本逐行喂、按时序抛断流，
 * 真走 RetryPolicy、GenerationSlot、IdleWatchdog、SSE 解析的每一条分支。
 *
 * 为什么值得这样测（"别是摆设"的验收口径）：六个部件各自的单元测试全绿，
 * 不代表接线正确 —— 这里钉的是**流程级事实**：重发了几次、每次等了多久、
 * 红线在哪个字节数拦下、停止为什么不算失败、槽在每种收场后是不是真空了。
 * 唯一的假象是 socket 本身（假 transport），其余全是被测的真代码。
 *
 * （Harness 必须 inner：非 inner 的嵌套类看不见外层实例属性，CI 编译段抓过。）
 */
class ChatWireRunnerTest {

    private val req = WireRequest(url = "https://example.invalid/v1/chat/completions", method = "POST", body = "{}")

    private sealed class Step {
        class Stream(val lines: List<String>, val bytes: Long) : Step()
        class Break(val lines: List<String>, val bytesSoFar: Long, val error: Throwable) : Step()
        class Status(val code: Int, val body: String? = null, val retryAfterMs: Long? = null) : Step()
        class Boom(val error: Throwable) : Step()
    }

    private class FakeTransport(private val steps: List<Step>) : WireTransport {
        var calls = 0
        var cancelled = false
        val sentRequests = mutableListOf<WireRequest>()

        override fun exchange(request: WireRequest, sink: LineSink): WireResponse {
            sentRequests += request
            val step = steps[minOf(calls, steps.lastIndex)]
            calls += 1
            return when (step) {
                is Step.Stream -> {
                    step.lines.forEach { line ->
                        if (!sink.onLine(line)) return WireResponse(200, null, step.bytes, null)
                    }
                    WireResponse(200, null, step.bytes, null)
                }
                is Step.Break -> {
                    step.lines.forEach { sink.onLine(it) }
                    throw WireStreamIOException(step.bytesSoFar, step.error)
                }
                is Step.Status -> WireResponse(step.code, step.retryAfterMs, 0L, step.body)
                is Step.Boom -> throw step.error
            }
        }

        override fun cancel() { cancelled = true }
        override fun isCancelled(): Boolean = cancelled
    }

    private inner class Harness(steps: List<Step>) {
        val transport = FakeTransport(steps)
        val slot = GenerationSlot()
        val waits = mutableListOf<Long>()
        val runner = ChatWireRunner(
            transport = transport,
            slot = slot,
            // 小基数退避：测试不等真秒，只验「等了多久、等了几次」。
            policy = RetryPolicy(maxAutomaticRetries = 2, backoffBaseMs = 10L, backoffMaxMs = 50L),
            watchdog = IdleWatchdog(IdleWatchdog.GENERATION_IDLE_MS),
            sleeper = { ms -> waits += ms },
        )

        fun run(cost: RequestCost = RequestCost.Costly, onText: (String) -> Unit = {}): ChatRunResult =
            runner.run(req, cost, onText)

        fun assertSlotFreed(file: String = "run") {
            assertFalse("$file 收场后槽必须空着（旧仓只能重开 App 的病根）", slot.isHolding())
        }
    }

    private fun dataLine(content: String) = """data: {"choices":[{"delta":{"content":"$content"}}]}"""
    private val doneLine = "data: [DONE]"

    @Test
    fun happyPathStreamsTextAndFreesSlot() {
        val h = Harness(listOf(Step.Stream(listOf(dataLine("你好"), dataLine("，世界"), doneLine), bytes = 128L)))

        val text = StringBuilder()
        val result = h.run { chunk -> text.append(chunk) }

        assertEquals(ChatRunResult.Ok, result)
        assertEquals("你好，世界", text.toString())
        assertEquals(1, h.transport.calls)
        h.assertSlotFreed()
    }

    @Test
    fun streamWithoutDoneMarkerIsProvablyIncomplete() {
        // 网关在内容块边界掐线：HTTP 200、有字、没收尾标记 —— 只看状态码发现不了
        val h = Harness(listOf(Step.Stream(listOf(dataLine("说到一半")), bytes = 60L)))

        val result = h.run()

        val err = (result as ChatRunResult.Failed).error
        assertTrue("该报半路断：$err", err is GenerationError.IncompleteStream)
        assertTrue(err.userMessage().contains("不完整"))
        h.assertSlotFreed()
    }

    @Test
    fun lengthFinishReasonBecomesOutputTruncated() {
        val finish = """data: {"choices":[{"delta":{},"finish_reason":"length"}]}"""
        val h = Harness(listOf(Step.Stream(listOf(dataLine("一堆字"), finish), bytes = 200L)))

        val err = (h.run() as ChatRunResult.Failed).error

        assertTrue("撞上限该指名道姓：$err", err is GenerationError.OutputTruncated)
        assertTrue(err.userMessage().contains("上限"))
        h.assertSlotFreed()
    }

    @Test
    fun rateLimitedWaitsRetryAfterThenSucceeds() {
        val h = Harness(
            listOf(
                Step.Status(429, body = """{"error":{"message":"slow down"}}""", retryAfterMs = 2_000L),
                Step.Stream(listOf(dataLine("好的"), doneLine), bytes = 30L),
            ),
        )

        assertEquals(ChatRunResult.Ok, h.run())

        // 退避顶 50 毫秒：2000 的 Retry-After 被政策封顶，等的是封顶值不是照抄。
        assertEquals("429 之后照 Retry-After 等（封顶 50）", listOf(50L), h.waits)
        assertEquals(2, h.transport.calls)
        h.assertSlotFreed()
    }

    @Test
    fun costlyPartialStreamNeverBlindRetries() {
        val h = Harness(
            listOf(Step.Break(listOf(dataLine("出了一些字")), bytesSoFar = 123L, error = IOException("reset"))),
        )

        val err = (h.run(RequestCost.Costly) as ChatRunResult.Failed).error

        assertEquals("花钱请求只准试一次：红线拦盲重", 1, h.transport.calls)
        assertTrue("理由必须带上已收字节：$err", err.userMessage().contains("123"))
        assertTrue(err.userMessage().contains("卡住") || err.userMessage().contains("盲重") || err.userMessage().contains("重复"))
        h.assertSlotFreed()
    }

    @Test
    fun freeRequestThatBreaksMidStreamIsRetried() {
        val h = Harness(
            listOf(
                Step.Break(listOf(dataLine("半截")), bytesSoFar = 1_000L, error = IOException("reset")),
                Step.Stream(listOf(dataLine("重来成了"), doneLine), bytes = 40L),
            ),
        )

        val text = StringBuilder()
        assertEquals(ChatRunResult.Ok, h.run(RequestCost.Free) { text.append(it) })

        assertEquals("免费请求允许续跑", 2, h.transport.calls)
        assertEquals(listOf(10L), h.waits)
        h.assertSlotFreed()
    }

    @Test
    fun contextOverflowGivesUpWithActionableAdvice() {
        val h = Harness(
            listOf(
                Step.Status(
                    400,
                    body = """{"error":{"message":"This model's maximum context length is 8192 tokens"}}""",
                ),
            ),
        )

        val err = (h.run(RequestCost.Free) as ChatRunResult.Failed).error

        assertEquals("超限连免费都不重发", 1, h.transport.calls)
        assertTrue("出路必须指名「删内容或开新会话」：$err", err.userMessage().contains("新会话"))
        h.assertSlotFreed()
    }

    @Test
    fun userStopMidStreamIsCancelledNotFailure() {
        // 模拟按停止：slot.stop 会调 transport.cancel，假实现置旗后把在跑的流掐了
        val h = Harness(
            listOf(Step.Break(listOf(dataLine("正说着")), bytesSoFar = 50L, error = SocketTimeoutException("停了"))),
        )
        // 预置取消旗：exchange 抛断流时 isCancelled 已为真（等价于 stop 发生在读流期间）
        h.transport.cancel()

        val err = (h.run() as ChatRunResult.Failed).error

        assertTrue("停止不许报成失败：$err", err is GenerationError.Cancelled)
        assertTrue(err.userMessage().contains("停止"))
        assertEquals(1, h.transport.calls)
        h.assertSlotFreed()
    }

    @Test
    fun busySlotRefusesWithoutTouchingNetwork() {
        val h = Harness(listOf(Step.Stream(listOf(doneLine), bytes = 1L)))
        val holder = h.slot.tryBegin()!!

        val err = (h.run() as ChatRunResult.Failed).error

        assertTrue("占着槽该先说清：$err", err is GenerationError.Configuration)
        assertEquals("没碰网络", 0, h.transport.calls)
        h.slot.end(holder)
        h.assertSlotFreed()
    }

    @Test
    fun midStreamErrorChunkIsNotSuccess() {
        // 200 外壳里塞 error 块：当成功返回就是骗人
        val h = Harness(
            listOf(
                Step.Stream(
                    listOf(dataLine("开了个头"), """data: {"error":{"message":"上游炸了"}}"""),
                    bytes = 70L,
                ),
            ),
        )

        val err = (h.run() as ChatRunResult.Failed).error

        assertTrue("流中错误要原样带话：$err", err.userMessage().contains("上游炸了"))
        h.assertSlotFreed()
    }

    @Test
    fun oversizeLineSurfacesSseParseInsteadOfHanging() {
        val h = Harness(listOf(Step.Boom(WireLimitException("单行超过上限"))))

        val err = (h.run() as ChatRunResult.Failed).error

        assertTrue("行超限归 SseParse：$err", err is GenerationError.SseParse)
        assertEquals("这种事实不劳驾重试表", 1, h.transport.calls)
        h.assertSlotFreed()
    }

    @Test
    fun retryBudgetSpendsThenStopsAndCounts() {
        val h = Harness(listOf(Step.Status(502, body = "bad gateway")))

        val err = (h.run(RequestCost.Free) as ChatRunResult.Failed).error

        assertEquals("预算 2 次：共发 3 回", 3, h.transport.calls)
        assertEquals("退避按 10、20 递增", listOf(10L, 20L), h.waits)
        assertTrue("放弃要报数：$err", err.userMessage().contains("502"))
        h.assertSlotFreed()
    }

    @Test
    fun silentReadTimeoutOnCostlyStreamSaysOneRetryAway() {
        val h = Harness(listOf(Step.Boom(WireStreamIOException(0L, SocketTimeoutException("五分钟没字节")))))

        val err = (h.run(RequestCost.Costly) as ChatRunResult.Failed).error

        assertEquals(1, h.transport.calls)
        assertTrue("零字节卡死要给一键重试的出口：$err", err.userMessage().contains("重试"))
        h.assertSlotFreed()
    }

    @Test
    fun requestsReachTransportUnchanged() {
        val h = Harness(listOf(Step.Stream(listOf(doneLine), bytes = 1L)))

        h.run()

        assertEquals(listOf(req), h.transport.sentRequests)
        h.assertSlotFreed()
    }
}
