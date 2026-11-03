package com.hualuo.engine.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 重试决策表的穷举测试。每条都写清「为什么必须这样」，因为这些规则背后全是旧仓真发生过的事故：
 * 半死的流被盲重，内容重复加 token 双花；500 被当瞬时，拿确定性 bug 撞运气；
 * 无限重试，把网关打死还看不出是谁干的。
 */
class RetryPolicyTest {

    private val policy = RetryPolicy(maxAutomaticRetries = 2, backoffBaseMs = 1_000L, backoffMaxMs = 30_000L)

    private fun outcome(
        attempt: Int = 1,
        cost: RequestCost = RequestCost.Costly,
        status: Int,
        body: String = "",
        bytes: Long = 0L,
        retryAfterMs: Long? = null,
        stalled: Boolean = false,
        local: FailureClass? = null,
    ) = RetryPolicy.AttemptOutcome(
        attempt = attempt,
        cost = cost,
        status = status,
        body = body,
        bytesReceived = bytes,
        retryAfterMs = retryAfterMs,
        stalled = stalled,
        localFailure = local,
    )

    private fun RetryDecision.mustRetry(): RetryDecision.Retry = this as RetryDecision.Retry

    private fun RetryDecision.mustGiveUp(): RetryDecision.GiveUp = this as RetryDecision.GiveUp

    @Test
    fun userCancellationIsNotAFailureAndNeverRetries() {
        val d = policy.decide(outcome(status = 0, local = FailureClass.Cancelled)).mustGiveUp()

        assertEquals(FailureClass.Cancelled, d.failure)
        assertTrue("得说清是用户自己取消的：${d.reason}", d.reason.contains("取消"))
    }

    @Test
    fun contextOverflowNeverRetriesEvenOnTransientStatus() {
        val d = policy.decide(outcome(status = 502, body = "maximum context length is 8192 tokens")).mustGiveUp()

        assertEquals(FailureClass.ContextOverflow, d.failure)
        assertTrue(d.reason.contains("新会话") || d.reason.contains("删内容"))
    }

    @Test
    fun twoHundredIsAcceptedNotRetried() {
        assertEquals(RetryDecision.Done, policy.decide(outcome(status = 200)))
    }

    @Test
    fun partialStreamOnCostlyRequestIsNeverBlindRetried() {
        val d = policy.decide(outcome(status = 524, bytes = 5_000L)).mustGiveUp()

        assertEquals(FailureClass.GatewayTimeout, d.failure)
        assertTrue("原因里必须带上已收字节数：${d.reason}", d.reason.contains("5000"))
        assertTrue(d.reason.contains("重复"))
    }

    @Test
    fun freeRequestThatStalledIsResumedNotMourned() {
        val retry = policy.decide(
            outcome(status = 200, cost = RequestCost.Free, bytes = 1_000L, stalled = true),
        ).mustRetry()

        assertEquals(2, retry.attempt)
        assertEquals(1_000L, retry.waitMs)
        assertTrue("免费请求要说续传：${retry.reason}", retry.reason.contains("续传"))
    }

    @Test
    fun costlyStalledWithBytesUsesTheBytesRule() {
        val d = policy.decide(outcome(status = 200, bytes = 900L, stalled = true)).mustGiveUp()

        assertEquals(FailureClass.Stalled, d.failure)
        assertTrue(d.reason.contains("900"))
    }

    @Test
    fun costlyStalledWithoutAnyBytesSaysItOutLoud() {
        val d = policy.decide(outcome(status = 200, bytes = 0L, stalled = true)).mustGiveUp()

        assertEquals(FailureClass.Stalled, d.failure)
        assertTrue("得告诉用户能一键重试：${d.reason}", d.reason.contains("重试"))
    }

    @Test
    fun noConnectionOnFreeRequestRetries() {
        val retry = policy.decide(outcome(status = 0, cost = RequestCost.Free)).mustRetry()

        assertEquals(2, retry.attempt)
        assertTrue(retry.reason.contains("没连上"))
    }

    @Test
    fun noConnectionOnCostlyRequestNeedsExplicitPermission() {
        val d = policy.decide(outcome(status = 0, cost = RequestCost.Costly)).mustGiveUp()

        assertEquals(FailureClass.NoConnection, d.failure)
        assertTrue("要说清默认不替用户花 token：${d.reason}", d.reason.contains("默认不"))

        val allowed = RetryPolicy(retryOnCostlyRequests = true)
        assertEquals(2, allowed.decide(outcome(status = 0, cost = RequestCost.Costly)).mustRetry().attempt)
    }

    @Test
    fun unknownCostIsTreatedAsTheExpensiveKind() {
        val d = policy.decide(outcome(status = 502, cost = RequestCost.Unknown))

        assertTrue("说不清代价就按花钱处理：$d", d is RetryDecision.GiveUp)
    }

    @Test
    fun rateLimitedWaitsForRetryAfterButCappedAtCeiling() {
        val short = policy.decide(outcome(status = 429, retryAfterMs = 5_000L)).mustRetry()
        assertEquals(5_000L, short.waitMs)

        val long = policy.decide(outcome(status = 429, retryAfterMs = 900_000L)).mustRetry()
        assertEquals("不许照抄一个 15 分钟的等待", 30_000L, long.waitMs)

        val spent = policy.decide(outcome(attempt = 3, status = 429, retryAfterMs = 1_000L)).mustGiveUp()
        assertEquals(FailureClass.RateLimited, spent.failure)
        assertTrue(spent.reason.contains("2 次"))
    }

    @Test
    fun clientErrorsAreNeverRetriedEvenForFreeRequests() {
        for (status in listOf(400, 401, 403, 404, 422)) {
            val d = policy.decide(outcome(status = status, cost = RequestCost.Free, body = "nope")).mustGiveUp()
            assertEquals("$status 该归用法错误", FailureClass.Client, d.failure)
        }
    }

    @Test
    fun serverBugCodesRetryOnlyForFreeRequests() {
        val free = policy.decide(outcome(status = 500, cost = RequestCost.Free)).mustRetry()
        assertEquals(2, free.attempt)

        val costly = policy.decide(outcome(status = 500, cost = RequestCost.Costly)).mustGiveUp()
        assertEquals(FailureClass.Gateway, costly.failure)
        assertTrue("得说明为什么不对花钱请求重发：${costly.reason}", costly.reason.contains("花钱"))
    }

    @Test
    fun gatewayCodesRetryForFreeAndGateForCostly() {
        for (status in listOf(502, 503, 504, 520, 522, 524, 525, 598, 599)) {
            val free = policy.decide(outcome(status = status, cost = RequestCost.Free))
            assertTrue("$status 免费请求该重来", free is RetryDecision.Retry)

            val costly = policy.decide(outcome(status = status, cost = RequestCost.Costly))
            assertTrue("$status 花钱请求默认不自动重发", costly is RetryDecision.GiveUp)
            val reason = costly.mustGiveUp().reason
            assertTrue("$status 的放弃理由不能只有一句「请求失败」", reason.contains(status.toString()) || reason.contains("默认不"))
        }
    }

    @Test
    fun retryBudgetStopsAndSaysHowManyTimes() {
        val d = policy.decide(outcome(attempt = 3, status = 502, cost = RequestCost.Free)).mustGiveUp()

        assertEquals(FailureClass.Gateway, d.failure)
        assertTrue("得报数：${d.reason}", d.reason.contains("已自动重试 2 次"))
    }

    @Test
    fun nonRetryableStatusGivesUpWithCategory() {
        val d = policy.decide(outcome(status = 302, cost = RequestCost.Free)).mustGiveUp()

        assertEquals(FailureClass.Unknown, d.failure)
        assertTrue(d.reason.contains("不属于"))
    }

    @Test
    fun backoffDoublesThenStopsAtCeilingWithoutOverflow() {
        val p = RetryPolicy(backoffBaseMs = 1_000L, backoffMaxMs = 8_000L)

        assertEquals(1_000L, p.backoffMs(1))
        assertEquals(2_000L, p.backoffMs(2))
        assertEquals(4_000L, p.backoffMs(3))
        assertEquals(8_000L, p.backoffMs(4))
        assertEquals(8_000L, p.backoffMs(5))
        assertEquals("天文数字的 attempt 也不能溢出", 8_000L, p.backoffMs(999))
    }

    @Test
    fun impossiblePoliciesAreRejectedAtConstruction() {
        try {
            RetryPolicy(maxAutomaticRetries = -1)
            fail("负次数本该抛")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("负"))
        }
        try {
            RetryPolicy(backoffBaseMs = 5_000L, backoffMaxMs = 1_000L)
            fail("上限小于基数本该抛")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("不能小于"))
        }
    }

    @Test
    fun impossibleOutcomesAreRejectedAtConstruction() {
        try {
            RetryPolicy.AttemptOutcome(attempt = 0, cost = RequestCost.Free, status = 200)
            fail("attempt 从 1 开始")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("attempt"))
        }
        try {
            RetryPolicy.AttemptOutcome(attempt = 1, cost = RequestCost.Free, status = 200, bytesReceived = -5L)
            fail("负字节数")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("字节"))
        }
    }

    @Test
    fun decisionCarriesTheStatusIntoTheReason() {
        val d = policy.decide(outcome(status = 524, cost = RequestCost.Costly)).mustGiveUp()

        assertTrue("理由里要能看到 524 这个码：${d.reason}", d.reason.contains("524"))
    }
}
