package com.hualuo.engine.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成槽与空闲看门狗的纯 JVM 测试（17 条）。
 *
 * 排布按「旧仓怎么死的」来排：先证明长传输不会被害，再证明卡死会被抓，
 * 最后证明**任何收场都放槽**（旧仓那条只能重开 App 的路，就是死在只覆盖了正常返回）。
 */
class GenerationSlotTest {

    /** 手动时钟：把「四小时」和「60 秒」都变成一次加法。 */
    private class Clock(var now: Long = 0L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    private class Handle {
        var cancels = 0
        var boom: String? = null

        fun cancellable(): Cancellable =
            Cancellable {
                val reason = boom
                if (reason != null) throw IllegalStateException(reason)
                cancels += 1
            }
    }

    // ── 看门狗：只量静默 ────────────────────────────────────────────────────

    @Test
    fun fourHourTransferWithSteadyBytesNeverStalls() {
        val clock = Clock()
        val dog = IdleWatchdog(IdleWatchdog.TRANSFER_IDLE_MS, clock::now)

        var elapsed = 0L
        while (elapsed < 4L * 60 * 60 * 1000) {
            dog.beat()
            clock.advance(30_000L)
            elapsed += 30_000L
            assertFalse("字节在流就不该判卡死（第 $elapsed 毫秒）", dog.stalled(clock.now))
        }
        assertEquals(30_000L, dog.idleMs())
    }

    @Test
    fun silencePastLimitIsStalledExactlyAtBoundary() {
        val clock = Clock()
        val dog = IdleWatchdog(60_000L, clock::now)

        clock.advance(59_999L)
        assertFalse(dog.stalled(clock.now))
        assertEquals("还差 1 毫秒的余地", 1L, dog.remainingMs(clock.now))

        clock.advance(1L)
        assertTrue(dog.stalled(clock.now))
        assertEquals(0L, dog.remainingMs(clock.now))
    }

    @Test
    fun unlimitedModeNeverStallsButMustBeChosenOnPurpose() {
        val clock = Clock()
        val dog = IdleWatchdog(IdleWatchdog.UNLIMITED, clock::now)

        clock.advance(30L * 24 * 60 * 60 * 1000)
        assertFalse(dog.stalled(clock.now))
        assertEquals(Long.MAX_VALUE, dog.remainingMs(clock.now))
        assertEquals(0L, IdleWatchdog.UNLIMITED)
    }

    @Test
    fun restartForgivesTheNewConnection() {
        val clock = Clock()
        val dog = IdleWatchdog(60_000L, clock::now)

        clock.advance(50_000L)
        dog.restart()
        clock.advance(50_000L)

        assertFalse("续传后拿旧连接的计时器诬陷新连接", dog.stalled(clock.now))
        assertEquals(50_000L, dog.idleMs())
    }

    @Test
    fun negativeLimitIsRejectedAtTheCallSite() {
        try {
            IdleWatchdog(-1L)
            org.junit.Assert.fail("负数上限本该抛")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("负数"))
        }
    }

    @Test
    fun defaultTiersArePinned() {
        // 改这两个数等于改产品行为（静默多久算死），必须是有意识的动作
        assertEquals(300_000L, IdleWatchdog.GENERATION_IDLE_MS)
        assertEquals(60_000L, IdleWatchdog.TRANSFER_IDLE_MS)
    }

    // ── 槽：唯一持有者与令牌 ────────────────────────────────────────────────

    @Test
    fun singleHolderOnlyAndQueueWhenBusy() {
        val slot = GenerationSlot()

        val claim = slot.tryBegin()
        assertNotNull(claim)
        assertTrue(slot.isHolding())
        assertNull("有人占着就该给 null 让调用方排队", slot.tryBegin())
        assertEquals(claim, slot.currentToken())

        assertTrue(slot.end(claim!!))
        assertFalse(slot.isHolding())
        assertNotNull(slot.tryBegin())
    }

    @Test
    fun staleTokenCannotFreeNewHoldersSlot() {
        val slot = GenerationSlot()
        val first = slot.tryBegin()!!

        slot.stop()
        val second = slot.tryBegin()!!

        assertFalse("旧协程不许放掉新主人的槽", slot.end(first))
        assertEquals(second, slot.currentToken())
        assertTrue(slot.isHolding())
    }

    @Test
    fun attachSwapsHandleWithoutChangingToken() {
        val slot = GenerationSlot()
        val first = Handle()
        val second = Handle()
        val claim = slot.tryBegin(first.cancellable())!!

        slot.attach(second.cancellable())
        val outcome = slot.stop()

        assertTrue(outcome.cancelled)
        assertEquals("换句柄不该取消旧的", 0, first.cancels)
        assertEquals("该取消现在挂着的", 1, second.cancels)
        assertEquals(claim + 1, outcome.token)
    }

    @Test
    fun stopIsIdempotentAndVoiced() {
        val slot = GenerationSlot()

        val empty = slot.stop()
        assertFalse(empty.hadHolder)
        assertFalse(empty.cancelled)
        assertNull(empty.cancelError)

        slot.tryBegin()
        val real = slot.stop()
        assertTrue(real.hadHolder)
        assertFalse(slot.isHolding())

        val again = slot.stop()
        assertFalse("第二次停已经没有持有者了", again.hadHolder)
    }

    @Test
    fun cancelFailureIsReportedAndStillFreesTheSlot() {
        val slot = GenerationSlot()
        val handle = Handle()
        handle.boom = "流炸了"
        slot.tryBegin(handle.cancellable())

        val outcome = slot.stop()

        assertNotNull("取消失败必须带原因", outcome.cancelError)
        assertTrue(outcome.cancelError!!.contains("流炸了"))
        assertFalse("但槽照样得腾出来 —— 不能因为取消失败就一直挂着", slot.isHolding())
    }

    // ── 槽：任何收场都放槽（旧仓的病根） ────────────────────────────────────

    @Test
    fun runGuardedReleasesSlotWhenBlockThrows() {
        val slot = GenerationSlot()
        val claim = slot.tryBegin()!!

        val result = slot.runGuarded(claim) { throw IllegalStateException("生成到一半断了") }

        assertNotNull("异常原样带出去，不吞", result.failure)
        assertTrue("抛了也必须放槽", result.released)
        assertFalse(result.stranded)
        assertFalse("槽必须空出来，界面才能再发下一条", slot.isHolding())
        assertNull(result.value)
    }

    @Test
    fun runGuardedVoicesStrandingWhenSuperseded() {
        val slot = GenerationSlot()
        val first = slot.tryBegin()!!
        slot.stop()
        val second = slot.tryBegin()!!

        val result = slot.runGuarded(first) { "旧的那次算完了" }

        assertFalse(result.released)
        assertTrue("被顶替必须出声：不能静默留下转圈假象", result.stranded)
        assertEquals("旧协程的结果照样带回去（供写检查点判断）", "旧的那次算完了", result.value)
        assertEquals(second, slot.currentToken())
    }

    @Test
    fun runGuardedReturnsValueAndFreesSlot() {
        val slot = GenerationSlot()
        val claim = slot.tryBegin()!!

        val result = slot.runGuarded(claim) { 7 * 6 }

        assertEquals(42, result.value)
        assertNull(result.failure)
        assertTrue(result.released)
        assertFalse(slot.isHolding())
    }

    @Test
    fun runGuardedStillReleasesWhenUserStoppedMidway() {
        // 旧仓最隐蔽的一条：用户按停止，槽被 stop 腾出，新一次生成占位；
        // 旧协程收尾时既不能放掉新槽，也不能让界面停在「还在生成」。
        val slot = GenerationSlot()
        val oldClaim = slot.tryBegin()!!
        slot.stop()
        val newClaim = slot.tryBegin()!!

        val old = slot.runGuarded(oldClaim) { "半截内容" }
        assertTrue(old.stranded)
        assertTrue("新主人的槽不能被动", slot.isHolding())
        assertEquals(newClaim, slot.currentToken())

        val fresh = slot.runGuarded(newClaim) { "完整内容" }
        assertTrue(fresh.released)
        assertFalse(slot.isHolding())
    }

    // ── 槽与看门狗 ──────────────────────────────────────────────────────────

    @Test
    fun slotUsesWatchdogAndBeatClearsIt() {
        val clock = Clock()
        val dog = IdleWatchdog(IdleWatchdog.GENERATION_IDLE_MS, clock::now)
        val slot = GenerationSlot(dog)

        assertFalse("没人持有时不该报卡死", slot.stalled(clock.now))
        assertEquals(0L, slot.idleMs())

        val claim = slot.tryBegin()!!
        clock.advance(200_000L)
        assertFalse(slot.stalled(clock.now))
        slot.beat()
        clock.advance(299_999L)
        assertFalse(slot.stalled(clock.now))
        clock.advance(1L)
        assertTrue("静默满 5 分钟必须报卡死", slot.stalled(clock.now))
        assertTrue(slot.idleMs() >= IdleWatchdog.GENERATION_IDLE_MS)

        slot.end(claim)
        clock.advance(10_000_000L)
        assertFalse("放槽之后不再关心静默", slot.stalled(clock.now))
    }

    @Test
    fun slotWithoutWatchdogNeverStalls() {
        val slot = GenerationSlot()
        slot.tryBegin()
        assertTrue(slot.isHolding())
        assertFalse(slot.stalled())
        assertEquals(0L, slot.idleMs())
    }
}
