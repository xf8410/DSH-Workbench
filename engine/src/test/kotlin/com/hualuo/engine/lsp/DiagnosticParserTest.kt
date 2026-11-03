package com.hualuo.engine.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断解析的纯 JVM 契约：六族真实输出形状各钉一条、认不出计数、截断出声、
 * 空输出 = 真干净（不是解析器瞎了）、退出码原样带；
 * 外加两条「假条目防呆」：`-->` 定位行与纯数字位置片段都不许装成诊断。
 *
 * 家规修记（CI run 35500938235 咬过的疤）：注释里不许有裸码位箭头（U+2192 等）——
 * NoEmojiInSourceTest 逮的就是这个。错误归因刀记过同档的课，这刀又犯，
 * 从此本文件内的「所以然」一律用逗号或汉字写，不用符号箭头。
 */
class DiagnosticParserTest {

    @Test
    fun kotlincCliShapeParses() {
        val out = """
            src/main/kotlin/X.kt:12:5: error: unresolved reference: foo
            src/main/kotlin/Y.kt:3: warning: unused variable 'a'
        """.trimIndent()
        val report = DiagnosticParser.parse(out, "kotlinc", exitCode = 1)

        assertEquals(2, report.items.size)
        val first = report.items[0]
        assertEquals("src/main/kotlin/X.kt", first.file)
        assertEquals(12, first.line)
        assertEquals(5, first.column)
        assertEquals("error", first.severity)
        assertEquals("unresolved reference: foo", first.message)
        assertEquals("warning", report.items[1].severity)
        assertEquals(0, report.items[1].column)
        assertEquals(0, report.skippedLines)
        assertEquals(1, report.exitCode)
    }

    @Test
    fun gccShapeParses() {
        val out = "main.c:7:9: error: 'x' undeclared (first use in this function)"
        val report = DiagnosticParser.parse(out, "gcc", exitCode = 1)

        assertEquals(1, report.items.size)
        assertEquals("main.c", report.items[0].file)
        assertEquals(7, report.items[0].line)
        assertEquals(9, report.items[0].column)
        assertEquals("error", report.items[0].severity)
    }

    @Test
    fun javacShapeParses() {
        val out = "src/Main.java:10: error: cannot find symbol\n  symbol:   variable z"
        val report = DiagnosticParser.parse(out, "javac", exitCode = 1)

        assertEquals("javac", report.items[0].source)
        assertEquals("src/Main.java", report.items[0].file)
        assertEquals(10, report.items[0].line)
        assertEquals(0, report.items[0].column)
        // 第二行 `symbol:   variable z` 认不出，计数不静默
        assertEquals(1, report.skippedLines)
    }

    @Test
    fun rustcTwoLineShapeParses() {
        val out = "error[E0308]: mismatched types\n  --> src/lib.rs:42:17\n  |\n  = note: expected `i32`"
        val report = DiagnosticParser.parse(out, "rustc", exitCode = 1)

        assertEquals(1, report.items.size)
        val d = report.items[0]
        assertEquals("src/lib.rs", d.file)
        assertEquals(42, d.line)
        assertEquals(17, d.column)
        assertEquals("error", d.severity)
        assertEquals("mismatched types", d.message)
        // 两行式吃掉箭头行；`|` 与 `= note:` 行认不出，计入跳过
        assertTrue("未识别行要计数：${report.skippedLines}", report.skippedLines >= 2)
    }

    @Test
    fun goShapeParses() {
        val out = "./main.go:15:2: undefined: fmtPrintln"
        val report = DiagnosticParser.parse(out, "go", exitCode = 1)

        assertEquals(1, report.items.size)
        assertEquals("./main.go", report.items[0].file)
        assertEquals(15, report.items[0].line)
        assertEquals(2, report.items[0].column)
        assertEquals("error", report.items[0].severity)
    }

    @Test
    fun tscShapeParses() {
        val out = "src/app.ts(5,12): error TS2304: Cannot find name 'foo'."
        val report = DiagnosticParser.parse(out, "tsc", exitCode = 1)

        assertEquals(1, report.items.size)
        assertEquals("src/app.ts", report.items[0].file)
        assertEquals(5, report.items[0].line)
        assertEquals(12, report.items[0].column)
        assertEquals("error", report.items[0].severity)
    }

    @Test
    fun cleanBuildGivesEmptyWithoutExcerpt() {
        val report = DiagnosticParser.parse("", "kotlinc", exitCode = 0)

        assertTrue(report.items.isEmpty())
        assertNull("干净构建不该有原文片段（那是给认不出时查的）", report.unparsedExcerpt)
        assertEquals(0, report.skippedLines)
        assertFalse(report.truncated)
    }

    @Test
    fun garbageOutputGetsExcerptNotSilence() {
        val out = "? weird tool output\n???\nstill weird"
        val report = DiagnosticParser.parse(out, "kotlinc", exitCode = 1)

        assertTrue(report.items.isEmpty())
        assertNotNull("认不出要给原文片段（让人能查），不许静默", report.unparsedExcerpt)
        assertTrue(report.skippedLines >= 3)
    }

    @Test
    fun overLimitTruncatesAndSaysSo() {
        val out = (1..(DiagnosticParser.MAX_ITEMS + 20)).joinToString("\n") { "x.kt:$it:1: error: e$it" }
        val report = DiagnosticParser.parse(out, "kotlinc", exitCode = 1)

        assertEquals(DiagnosticParser.MAX_ITEMS, report.items.size)
        assertTrue("截断必须出声", report.truncated)
    }

    @Test
    fun arrowLocatorLineAloneIsNotADiagnostic() {
        // `-->` 是 rustc 两行式的零件；它单独出现时绝不许被装成一条诊断
        val report = DiagnosticParser.parse("  --> src/lib.rs:42:17", "gcc", exitCode = 1)

        assertTrue("--> 是定位片段不是诊断：${report.items}", report.items.isEmpty())
        assertEquals(1, report.skippedLines)
    }

    @Test
    fun barePositionFragmentTailIsNotADiagnostic() {
        // `src/X.kt:5:3` 是位置片段（有的工具会单独印一行），不是一条「说 3」的诊断
        val report = DiagnosticParser.parse("src/X.kt:5:3", "kotlinc", exitCode = 1)

        assertTrue("纯数字尾数不许装成诊断：${report.items}", report.items.isEmpty())
        assertEquals(1, report.skippedLines)
    }
}
