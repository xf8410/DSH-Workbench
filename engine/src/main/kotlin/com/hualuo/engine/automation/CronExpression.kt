package com.hualuo.engine.automation

import java.util.Calendar
import java.util.TimeZone

/**
 * 一条标准五字段 cron：`分 时 日 月 周`（语义整件对齐旧 Agora CronExpression）。
 *
 * 每字段支持：`*`、单值、列表（a,b）、区间（a-b）、步进（* /n、a-b/n、a/n 表示 a、a+n… 到字段上限）。
 * 周字段 0-6 且 0=周日；`7` 也当周日。月与周只认数字。
 *
 * 纯逻辑、时区显式——不带任何 Android 依赖，全量可单测。
 * 日与周同时受限时，**任一命中即匹配**（Vixie-cron 标准规则）。
 */
class CronExpression private constructor(
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>,
    private val domRestricted: Boolean,
    private val dowRestricted: Boolean,
) {
    /**
     * [afterMillis] 之后第一个匹配时刻（[zone] 时区评）。
     * 搜不到（8 年地平线内）回 null——覆盖「只 2 月 29 日」这类日程。
     * 结果的秒与毫秒归零。
     *
     * 走法：日 -> 时 -> 分逐级跳——整个跳过的日/时段里日期与小时字段恒定，
     * 段内任何分钟都不可能命中。最坏约 3 千次日探测（朴素逐分钟要 420 万步）。
     * 命中日内的逐分钟步进保持精确穿越 DST 空洞/重复——时刻单调向前，墙钟字段说了算。
     */
    fun next(afterMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = afterMillis
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, 1) // strictly after
        }
        val deadline = cal.timeInMillis + HORIZON_MILLIS
        while (cal.timeInMillis <= deadline) {
            if (!dayMatches(cal)) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                continue
            }
            if (cal.get(Calendar.HOUR_OF_DAY) !in hours) {
                cal.add(Calendar.HOUR_OF_DAY, 1)
                cal.set(Calendar.MINUTE, 0)
                continue
            }
            if (cal.get(Calendar.MINUTE) in minutes) return cal.timeInMillis
            cal.add(Calendar.MINUTE, 1)
        }
        return null
    }

    /** [cal] 的民用日是否满足月 + 日/周约束。 */
    private fun dayMatches(cal: Calendar): Boolean {
        if (cal.get(Calendar.MONTH) + 1 !in months) return false
        val dom = cal.get(Calendar.DAY_OF_MONTH) in daysOfMonth
        val dow = (cal.get(Calendar.DAY_OF_WEEK) - 1) in daysOfWeek // Calendar SUNDAY=1 -> 0
        return when {
            domRestricted && dowRestricted -> dom || dow // Vixie-cron OR 规则
            else -> dom && dow
        }
    }

    companion object {
        // 8 年（按墙钟表述）；合法表达式总在远早于此处收敛。
        private const val HORIZON_MILLIS = 366L * 24 * 60 * 8 * 60_000L

        /** 解析 [expr]；不是良构五字段回 null。 */
        fun parse(expr: String): CronExpression? {
            val parts = expr.trim().split(Regex("\\s+"))
            if (parts.size != 5) return null
            val minutes = parseField(parts[0], 0, 59) ?: return null
            val hours = parseField(parts[1], 0, 23) ?: return null
            val daysOfMonth = parseField(parts[2], 1, 31) ?: return null
            val months = parseField(parts[3], 1, 12) ?: return null
            val daysOfWeek = parseField(parts[4], 0, 7)?.map { if (it == 7) 0 else it }?.toSet() ?: return null
            return CronExpression(
                minutes, hours, daysOfMonth, months, daysOfWeek,
                domRestricted = parts[2].trim() != "*",
                dowRestricted = parts[4].trim() != "*",
            )
        }

        fun isValid(expr: String): Boolean = parse(expr) != null

        /** 展开一个字段（"*"、"a"、"a,b"、"a-b"、"* /n"、"a-b/n"、"a/n"）到取值集。 */
        private fun parseField(field: String, min: Int, max: Int): Set<Int>? {
            val result = sortedSetOf<Int>()
            for (token in field.split(",")) {
                if (token.isEmpty()) return null
                val (rangePart, stepPart) = token.split("/").let {
                    when (it.size) {
                        1 -> it[0] to null
                        2 -> it[0] to it[1]
                        else -> return null
                    }
                }
                val step = stepPart?.toIntOrNull()?.takeIf { it > 0 } ?: if (stepPart == null) 1 else return null
                val (start, end) = when {
                    rangePart == "*" -> min to max
                    rangePart.contains("-") -> {
                        val r = rangePart.split("-")
                        if (r.size != 2) return null
                        val a = r[0].toIntOrNull() ?: return null
                        val b = r[1].toIntOrNull() ?: return null
                        a to b
                    }
                    else -> {
                        val v = rangePart.toIntOrNull() ?: return null
                        // 裸值带步进（a/n）：从 a 跑到字段上限。
                        v to (if (stepPart != null) max else v)
                    }
                }
                if (start < min || end > max || start > end) return null
                var v = start
                while (v <= end) {
                    result.add(v)
                    v += step
                }
            }
            return result.ifEmpty { null }
        }
    }
}
