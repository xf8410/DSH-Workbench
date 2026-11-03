package com.hualuo.engine.observe

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 连接状态机测试：六态转移、单并发、退避冷却——时钟从外面喂，可拨表。
 */
class ObserveStateTest {

    private class Clock(var now: Long = 0L) {
        fun tick(ms: Long) { now += ms }
        fun fn(): () -> Long = { now }
    }

    @Test
    fun `初始未连接，探测许可后进 CONNECTING`() {
        val s = ObserveState(Clock().fn())
        assertEquals(ObserveState.Link.DISCONNECTED, s.link)
        assertTrue(s.tryBeginProbe())
        assertEquals(ObserveState.Link.CONNECTING, s.link)
    }

    @Test
    fun `单并发：探测中再抢被拒`() {
        val s = ObserveState(Clock().fn())
        assertTrue(s.tryBeginProbe())
        assertFalse(s.tryBeginCall())
        s.settleProbe(healthOk = true, statusOk = true, healthStatus = 200, note = "ok")
        assertEquals(ObserveState.Link.READY, s.link)
    }

    @Test
    fun `全通转 READY 且清冷却`() {
        val s = ObserveState(Clock().fn())
        s.settleProbe(healthOk = true, statusOk = true, healthStatus = 200, note = "ok")
        assertEquals(ObserveState.Link.READY, s.link)
        assertEquals(0L, s.cooldownRemainingMs())
    }

    @Test
    fun `health 败转 DISCONNECTED 并进退避`() {
        val s = ObserveState(Clock().fn())
        s.settleProbe(healthOk = false, statusOk = false, healthStatus = null, note = "无响应")
        assertEquals(ObserveState.Link.DISCONNECTED, s.link)
        assertTrue(s.cooldownRemainingMs() > 0)
        // 冷却期内探测被拒（熔断）
        assertFalse(s.tryBeginProbe())
    }

    @Test
    fun `过载转 OVERLOADED 且冷却更长`() {
        val s = ObserveState(Clock().fn())
        s.settleProbe(healthOk = false, statusOk = false, healthStatus = 503, note = "忙")
        assertEquals(ObserveState.Link.OVERLOADED, s.link)
        assertTrue(s.cooldownRemainingMs() >= 60_000)
    }

    @Test
    fun `status 败转 DEGRADED 且可继续调用`() {
        val s = ObserveState(Clock().fn())
        s.settleProbe(healthOk = true, statusOk = false, healthStatus = 200, note = "status 不给力")
        assertEquals(ObserveState.Link.DEGRADED, s.link)
        assertTrue(s.tryBeginCall())
        s.settleCall(true, "顺")
        assertTrue(s.tryBeginCall()) // settleCall 已收线，DEGRADED 可继续调用
    }

    @Test
    fun `连败退避翻倍封顶五分钟`() {
        val clock = Clock()
        val s = ObserveState(clock.fn())
        s.settleProbe(healthOk = false, statusOk = false, healthStatus = null, note = "败1")
        val c1 = s.cooldownRemainingMs()
        clock.tick(c1 + 1)
        s.settleProbe(healthOk = false, statusOk = false, healthStatus = null, note = "败2")
        val c2 = s.cooldownRemainingMs()
        assertTrue("第二次冷却应不低于第一次（翻倍起步）", c2 >= c1)
        // 连败到封顶
        repeat(10) {
            clock.tick(s.cooldownRemainingMs() + 1)
            s.settleProbe(healthOk = false, statusOk = false, healthStatus = null, note = "败${it + 3}")
        }
        assertTrue("封顶 5 分钟", s.cooldownRemainingMs() <= 300_000L)
    }

    @Test
    fun `冷却过后可再探测`() {
        val clock = Clock()
        val s = ObserveState(clock.fn())
        s.settleProbe(healthOk = false, statusOk = false, healthStatus = null, note = "败")
        clock.tick(s.cooldownRemainingMs() + 1)
        assertTrue(s.tryBeginProbe())
    }

    @Test
    fun `DISCONNECTED 态拒工具调用`() {
        val s = ObserveState(Clock().fn())
        assertFalse(s.tryBeginCall())
        assertTrue(s.lastNote.contains("探测"))
    }

    @Test
    fun `READY 态工具失败不打断 READY`() {
        val s = ObserveState(Clock().fn())
        s.settleProbe(healthOk = true, statusOk = true, healthStatus = 200, note = "ok")
        assertTrue(s.tryBeginCall())
        s.settleCall(false, "一次偶发失败")
        assertEquals(ObserveState.Link.READY, s.link)
    }

    @Test
    fun `六态中文显示名`() {
        assertEquals("未连接", ObserveState.Link.DISCONNECTED.label)
        assertEquals("探测中", ObserveState.Link.CONNECTING.label)
        assertEquals("已就绪", ObserveState.Link.READY.label)
        assertEquals("降级", ObserveState.Link.DEGRADED.label)
        assertEquals("对端过载", ObserveState.Link.OVERLOADED.label)
        assertEquals("疑似非 hlpatch", ObserveState.Link.INCOMPATIBLE.label)
    }
}
