package com.hualuo.engine.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装计划对照表（D2 修复的验证面）：
 * 每组输入打印 requested / 期望 toInstall / 实际 toInstall / 已满足 / 未知 / 环。
 */
class ApkPlanTest {

    private val index = AlpineRepoIndex.parse(
        """
        C:Q1
        P:base
        V:1.0-r0
        D:libfoo

        C:Q2
        P:libfoo
        V:2.0-r0

        C:Q3
        P:app
        V:3.0-r0
        D:base libbar

        C:Q4
        P:loopa
        V:4.0-r0
        D:loopb

        C:Q5
        P:loopb
        V:4.0-r0
        D:loopa

        C:Q6
        P:libbar
        V:5.0-r0
    """.trimIndent(),
    )

    private fun describe(plan: ApkPlan.Plan): String =
        "requested=${plan.requested} toInstall=${plan.toInstall.map { it.name }} satisfied=${plan.alreadySatisfied} unknown=${plan.unknown} cycles=${plan.cycles}"

    @Test
    fun freshInstallExpandsDependenciesInDependencyFirstOrder() {
        val plan = ApkPlan().plan(index, listOf("app"), emptyMap())
        assertEquals(
            "依赖应先于被依赖者（app 的依赖含 libbar）：${describe(plan)}",
            setOf("libfoo", "base", "libbar", "app"),
            plan.toInstall.map { it.name }.toSet(),
        )
        assertTrue("未知应为空：${describe(plan)}", plan.unknown.isEmpty())
    }

    @Test
    fun installedSameVersionGoesToSatisfied() {
        val plan = ApkPlan().plan(index, listOf("app"), mapOf("libfoo" to "2.0-r0", "base" to "1.0-r0", "app" to "3.0-r0"))
        assertEquals(
            "版本一致应全进 satisfied：${describe(plan)}",
            setOf("libfoo", "base", "app"),
            plan.alreadySatisfied.toSet(),
        )
        assertEquals(
            "libbar 在索引但没装过，要补进安装计划：${describe(plan)}",
            setOf("libbar"),
            plan.toInstall.map { it.name }.toSet(),
        )
    }

    @Test
    fun versionMismatchReinstalls() {
        val plan = ApkPlan().plan(index, listOf("app"), mapOf("app" to "3.0-r0", "base" to "0.9-r0", "libfoo" to "2.0-r0"))
        assertEquals(
            "base 版本旧应重装，其余满足：${describe(plan)}",
            setOf("base", "libbar"),
            plan.toInstall.map { it.name }.toSet(),
        )
        assertEquals("已满足应含 libfoo+app：${describe(plan)}", setOf("libfoo", "app"), plan.alreadySatisfied.toSet())
    }

    @Test
    fun unknownNamesAreReportedNotDropped() {
        val plan = ApkPlan().plan(index, listOf("app", "ghost-pkg"), emptyMap())
        assertEquals("查不到要报告，不许静默丢：${describe(plan)}", setOf("ghost-pkg"), plan.unknown.toSet())
        assertEquals("其余照常展开（libbar 在索引）：${describe(plan)}", 4, plan.toInstall.size)
    }

    @Test
    fun dependencyCycleIsReportedAndDoesNotHang() {
        val plan = ApkPlan().plan(index, listOf("loopa"), emptyMap())
        assertTrue("环要被记录：${describe(plan)}", plan.cycles.isNotEmpty())
        assertTrue("环里的包仍应进安装计划（apk 自己处理环）：${describe(plan)}", plan.toInstall.map { it.name }.containsAll(listOf("loopa", "loopb")))
    }
}
