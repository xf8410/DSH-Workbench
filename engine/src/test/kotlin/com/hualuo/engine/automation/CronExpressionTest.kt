package com.hualuo.engine.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * cron 解析与 next() 对照表（固定时区，全离线；用例对齐旧仓语义：Vixie OR 规则/步进/7=周日/地平线）。
 */
class CronExpressionTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    /** 造一个该时区的时刻：y-m-d h:m:s=0。 */
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance(zone).apply {
            set(y, mo - 1, d, h, mi, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun next(expr: String, from: Long): Long? = CronExpression.parse(expr)!!.next(from, zone)

    @Test
    fun parseFieldShapes() {
        assertTrue(CronExpression.isValid("0 9 * * *"))
        assertTrue(CronExpression.isValid("*/15 * * * *"))
        assertTrue(CronExpression.isValid("30 8-18 1,15 * *"))
        assertTrue(CronExpression.isValid("0 0 * * 7")) // 7 也是周日
        assertTrue(CronExpression.isValid("5/10 * * * *")) // 裸值带步进：5,15,25,...
        assertFalse("四字段不合法", CronExpression.isValid("0 9 * *"))
        assertFalse("分钟越界", CronExpression.isValid("60 * * * *"))
        assertFalse("步进为零", CronExpression.isValid("*/0 * * * *"))
        assertFalse("区间倒挂", CronExpression.isValid("50-10 * * * *"))
        assertFalse("非数字月", CronExpression.isValid("* * * Jan *"))
    }

    @Test
    fun nextEveryMinuteWalksStrictlyAfter() {
        val from = at(2026, 9, 24, 8, 0)
        assertEquals(at(2026, 9, 24, 8, 1), next("* * * * *", from))
        // 同一时刻本身不算（strictly after）
        assertEquals(at(2026, 9, 24, 8, 1), next("* * * * *", at(2026, 9, 24, 8, 0)))
    }

    @Test
    fun nextDailyNine() {
        val from = at(2026, 9, 24, 10, 30)
        assertEquals("当天九点已过，回明天九点", at(2026, 9, 25, 9, 0), next("0 9 * * *", from))
        assertEquals("九点整的下一秒在 9:01", at(2026, 9, 24, 9, 1), next("* * * * *", at(2026, 9, 24, 9, 0)))
    }

    @Test
    fun nextStepMinutes() {
        val from = at(2026, 9, 24, 8, 7)
        assertEquals("*/15 从 8:07 起是 8:15", at(2026, 9, 24, 8, 15), next("*/15 * * * *", from))
    }

    @Test
    fun nextDayOfWeekSunday() {
        // 2026-9-24 是周四；下一个周日是 9-27
        val from = at(2026, 9, 24, 12, 0)
        assertEquals(at(2026, 9, 27, 0, 0), next("0 0 * * 0", from))
        // 7 也是周日（同一条）
        assertEquals(at(2026, 9, 27, 0, 0), next("0 0 * * 7", from))
    }

    @Test
    fun vixieOrRuleWhenBothRestricted() {
        // 日与周同时受限：任一命中即匹配。
        // 9 月里 1 号是周二（周二不在周日集）——日=1 命中即可
        val from = at(2026, 8, 31, 12, 0)
        assertEquals(at(2026, 9, 1, 0, 0), next("0 0 1 * 0", from))
        // 而日与周都没限死时是 AND：0 0 * * * 里 * 与 *
        assertEquals(at(2026, 8, 31, 23, 0), next("0 23 * * *", from))
    }

    @Test
    fun february29Horizon() {
        // 只在 2 月 29 日零点：2026 年往后 8 年地平线内没有（2028 是闰年，但 2/29 在 2028——在地平线内！）
        val from = at(2026, 9, 24, 0, 0)
        val got = next("0 0 29 2 *", from)
        // 2028-02-29 存在且在 8 年内——必须算出来，不许 null
        assertEquals(at(2028, 2, 29, 0, 0), got)
        // 一个真正够不到的：2 月 30 日——永远 null
        assertNull("2 月 30 日不存在：", next("0 0 30 2 *", from))
    }

    @Test
    fun listAndRangeCompose() {
        // 30 8-18 1,15 * * ：从 9-24 12:00 起，下一个是 10-1 08:30（9 月的 1,15 都过了）
        val from = at(2026, 9, 24, 12, 0)
        assertEquals(at(2026, 10, 1, 8, 30), next("30 8-18 1,15 * *", from))
        // 同一天内顺延：9-24 17:00 起是 17:30？9-24 不是 1/15 号——还是 10-1
        assertEquals(at(2026, 10, 1, 8, 30), next("30 8-18 1,15 * *", at(2026, 9, 24, 17, 0)))
    }
}
