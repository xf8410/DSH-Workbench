package com.hualuo.engine.lsp

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查执行器契约（纯 JVM）：
 *  - 假缝隙下把超时、截断、账目三条纪律逐条钉死；
 *  - 真缝隙（ProcessBuilder）跑几条**真的短命令**（sh 脚本）验行为——
 *    这些命令凡有 POSIX shell 的机器都有，不依赖任何编译器，CI 与本地行为一致。
 *
 * 修记（推 CI 之前自查逮住的）：shell 测试命令里曾用 `$i` 与 `$((...))`——Kotlin 字符串模板
 * 会把它们当变量引用吃掉（未定义变量，编译必红）。教训与全仓老账同源：**Kotlin 字符串里的
 * 美元符号是语法，不是字面量**。现在测试命令一律选无美元符号的写法（yes 配 head 之类）。
 */
class LspCheckRunnerTest {

    private fun fakeLaunch(outcome: CheckOutcome) = CheckLaunch { _, _, _, _ -> outcome }

    private fun okOutcome(output: String) = CheckOutcome(
        exitCode = 0,
        output = output,
        timedOut = false,
        outputTruncated = false,
        sinkBytes = output.toByteArray().size.toLong(),
        durationMs = 12L,
    )

    @Test
    fun parsesWhatTheProcessPrinted() {
        val runner = LspCheckRunner(
            launch = fakeLaunch(okOutcome("a.kt:1:2: error: 这里过不去\n")),
        )
        val result = runner.run(listOf("whatever"), File("."), "kotlinc")

        assertEquals(1, result.report.items.size)
        assertEquals("a.kt", result.report.items[0].file)
        assertEquals(1, result.report.items[0].line)
        assertEquals("error", result.report.items[0].severity)
        assertTrue(result.judgeable)
    }

    @Test
    fun timeoutIsReportedNotSwallowed() {
        val runner = LspCheckRunner(
            launch = fakeLaunch(
                CheckOutcome(
                    exitCode = 137,
                    output = "",
                    timedOut = true,
                    outputTruncated = false,
                    sinkBytes = 0L,
                    durationMs = 60_000L,
                ),
            ),
        )
        val result = runner.run(listOf("whatever"), File("."), "kotlinc")

        assertTrue("超时必须在回执里：${result.outcome}", result.outcome.timedOut)
        assertFalse("超时不是「可判的结论」，调用方不许拿空列表当没问题", result.judgeable)
    }

    @Test
    fun truncationFlagSurvivesEvenWhenParserSeesCleanOutput() {
        val runner = LspCheckRunner(
            launch = fakeLaunch(
                CheckOutcome(
                    exitCode = 0,
                    output = "",
                    timedOut = false,
                    outputTruncated = true,
                    sinkBytes = 999_999L,
                    durationMs = 100L,
                ),
            ),
        )
        val result = runner.run(listOf("whatever"), File("."), "gcc")

        assertTrue("截断必须一路带到收场：${result.outcome}", result.outcome.outputTruncated)
        assertEquals(999_999L, result.outcome.sinkBytes)
    }

    @Test
    fun rejectsBadConstruction() {
        try {
            LspCheckRunner(timeoutMs = 0)
            throw AssertionError("0 超时本该拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue("拒绝要带原因", (e.message ?: "").isNotEmpty())
        }
        try {
            LspCheckRunner(maxOutputBytes = 0)
            throw AssertionError("0 输出上限本该拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue("拒绝要带原因", (e.message ?: "").isNotEmpty())
        }
    }

    @Test
    fun realProcessReadsOutputAndExitCode() {
        val dir = Files.createTempDirectory("lsp-check-real").toFile()
        val script = File(dir, "hello.sh")
        script.writeText("echo 'x.kt:3:5: error: synthetic failure'\nexit 0\n")

        val runner = LspCheckRunner(timeoutMs = 20_000L)
        val result = runner.run(listOf("/bin/sh", script.path), dir, "kotlinc")

        assertFalse(result.outcome.timedOut)
        assertEquals(0, result.outcome.exitCode)
        assertEquals(1, result.report.items.size)
        assertEquals(3, result.report.items[0].line)
        assertEquals(5, result.report.items[0].column)
        assertTrue("真进程的字节账不能为零", result.outcome.sinkBytes > 0L)
    }

    @Test
    fun realProcessTimeoutKillsAndReports() {
        val dir = Files.createTempDirectory("lsp-check-timeout").toFile()
        val runner = LspCheckRunner(timeoutMs = 800L)

        val result = runner.run(listOf("/bin/sh", "-c", "sleep 30"), dir, "go")

        assertTrue("30 秒的活 0.8 秒就该被杀：${result.outcome}", result.outcome.timedOut)
        assertFalse(result.judgeable)
    }

    @Test
    fun realProcessOutputIsCappedButStreamStaysDrained() {
        val dir = Files.createTempDirectory("lsp-check-cap").toFile()
        val runner = LspCheckRunner(timeoutMs = 20_000L, maxOutputBytes = 1024)

        // yes 无限冲刷（每行 32 字节），head 取 200 行后收线：无美元符号，绕过字符串模板雷区。
        val result = runner.run(
            listOf("/bin/sh", "-c", "yes aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa | head -n 200"),
            dir,
            "gcc",
        )

        assertFalse("排干是为了防管道死锁，不是等超时", result.outcome.timedOut)
        assertTrue("超封顶必须说出来：${result.outcome}", result.outcome.outputTruncated)
        assertTrue("实际读走的字节比存下的多", result.outcome.sinkBytes > 1024L)
        assertTrue("存下的文本不许超封顶", result.outcome.output.toByteArray().size <= 1024)
    }

    @Test
    fun realProcessMissingDirectoryIsRejected() {
        val runner = LspCheckRunner()
        try {
            runner.run(listOf("/bin/sh", "-c", "true"), File("/definitely/not/here"), "go")
            throw AssertionError("坏目录本该拒绝")
        } catch (e: CheckReject) {
            assertTrue("拒绝要带出路：${e.message}", (e.message ?: "").contains("工作目录"))
        }
    }

    @Test
    fun emptyCommandIsRejected() {
        val dir = Files.createTempDirectory("lsp-check-empty").toFile()
        try {
            LspCheckRunner().run(emptyList(), dir, "go")
            throw AssertionError("空命令本该拒绝")
        } catch (e: CheckReject) {
            assertTrue("拒绝要带原因", (e.message ?: "").isNotEmpty())
        }
    }
}
