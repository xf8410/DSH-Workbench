package com.hualuo.engine.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone
import java.util.Calendar

/**
 * 任务账本对照表（临时目录，离线）。
 */
class TaskStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun store(): TaskStore = TaskStore(File(tmp.root, "tasks.jsonl"), zone)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance(zone).apply {
            set(y, mo - 1, d, h, mi, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun createValidatesCronAndComputesNextRun() {
        val s = store()
        val now = at(2026, 9, 24, 10, 0)
        val r = s.create("晨报", "跑今天的晨报", "0 9 * * *", "", now)
        assertEquals("t$now-1", r.id)
        assertTrue(r.enabled)
        assertEquals("nextRun 建时就算好：", at(2026, 9, 25, 9, 0), r.nextRunMs)
        try {
            s.create("坏", "x", "99 * * * *", "", now)
            fail("坏 cron 必须当场拒")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cron 不合法"))
        }
    }

    @Test
    fun findByUniqueIdOrUniqueNameAndRejectAmbiguousName() {
        val s = store()
        val now = at(2026, 9, 24, 10, 0)
        val a = s.create("同名", "A", "0 9 * * *", "", now)
        val b = s.create("同名", "B", "0 9 * * *", "", now)
        assertEquals(b.id, s.delete(b.id).id) // id 精确
        assertEquals(a.id, s.setEnabled(a.name, false, now).id) // 唯一名可用
        // 现在只剩一条同名——不触发重名路径
        try {
            val c = s.create("同名", "C", "0 9 * * *", "", now)
            s.delete("同名") // 三条同名（A 停用还在账上）——重名必须拒
            fail("重名不许猜")
        } catch (e: IllegalArgumentException) {
            assertTrue("重名报账：${e.message}", e.message!!.contains("重名"))
        }
    }

    @Test
    fun disableKeepsRecordEnableRecomputes() {
        val s = store()
        val now = at(2026, 9, 24, 10, 0)
        val r = s.create("任务", "跑", "0 9 * * *", "", now)
        val off = s.setEnabled(r.id, false, now)
        assertTrue(!off.enabled)
        val back = s.setEnabled(r.id, true, at(2026, 9, 26, 12, 0))
        assertTrue(back.enabled)
        assertEquals("重新启用时 nextRun 按当下重算：", at(2026, 9, 27, 9, 0), back.nextRunMs)
    }

    @Test
    fun recordRunBooksLastAndNext() {
        val s = store()
        val now = at(2026, 9, 24, 9, 0)
        val r = s.create("任务", "跑", "0 9 * * *", "", now)
        val ranAt = at(2026, 9, 25, 9, 0)
        val after = s.recordRun(r.id, ranAt)
        assertEquals(ranAt, after!!.lastRunMs)
        assertEquals("跑完算下一次：", at(2026, 9, 26, 9, 0), after.nextRunMs)
        assertEquals(null, s.recordRun("ghost", ranAt))
    }

    @Test
    fun badLinesCountedNotFatal() {
        val f = File(tmp.root, "tasks.jsonl")
        val s = store()
        s.create("好任务", "x", "0 9 * * *", "", at(2026, 9, 24, 10, 0))
        // 蓄意追加两行坏账
        f.appendText("{half broken\nnot json at all\n")
        val listing = s.list()
        assertEquals(1, listing.tasks.size)
        assertEquals("坏行单独数出来：", 2, listing.unreadable)
    }

    @Test
    fun atomicRewriteSurvives() {
        val s = store()
        val now = at(2026, 9, 24, 10, 0)
        val a = s.create("A", "a", "0 9 * * *", "", now)
        val b = s.create("B", "b", "30 12 * * *", "", now)
        s.delete(a.id)
        // 删完重开一个新 store 实例验证盘上就是两条->删一条
        val reopened = TaskStore(File(tmp.root, "tasks.jsonl"), zone)
        val listing = reopened.list()
        assertEquals(1, listing.tasks.size)
        assertEquals(b.id, listing.tasks[0].id)
        assertEquals(0, listing.unreadable)
    }
}
