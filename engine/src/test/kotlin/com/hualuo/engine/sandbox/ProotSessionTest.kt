package com.hualuo.engine.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * proot 会话对照表（D4 busy 门 / L2 输出封顶排干 / L3 参数拒绝 / 超时强杀 / 终态行）。
 * 纯 JVM：真进程用 /bin/sh（与目标运行时同为 POSIX 进程语义），不 mock 进程对象。
 */
class ProotSessionTest {

    private fun session(launcher: ProotSession.Launcher = ProotSession.Launcher { cmd, dir ->
        // 测试桩：把 proot 命令转成直接跑 sh（首元素是 prootBinary 时跳过它和 -r rootfs 段）
        val shim = if (cmd.firstOrNull() == "proot") {
            val idx = cmd.indexOf("/bin/sh")
            cmd.subList(idx, cmd.size)
        } else cmd
        ProcessBuilder(shim).apply { dir?.let { directory(it) } }.start()
    }) = ProotSession(rootfsDir = File("/tmp/unused-rootfs"), launcher = launcher)

    @Test
    fun echoRoundTripCarriesStdoutAndTerminalLine() {
        val outcome = session().exec("echo hello-sandbox", 10_000L)
        assertEquals("退出码应为 0：$outcome", 0, outcome.exitCode)
        assertTrue("stdout 要含回显：stdout=${outcome.stdoutTail}", outcome.stdoutTail.contains("hello-sandbox"))
        assertTrue("终态行必须有：${outcome.terminalLine}", outcome.terminalLine.startsWith("终态："))
    }

    @Test
    fun emptyCommandIsRejectedNotRun() {
        val err = runCatching { session().exec("", 5_000L) }.exceptionOrNull()
        assertTrue("空命令必须 REJECTED：err=$err", err is SandboxException && err.error.kind == SandboxError.Kind.REJECTED)
    }

    @Test
    fun overlongCommandIsRejected() {
        val big = "x".repeat(ProotSession.MAX_COMMAND_CHARS + 1)
        val err = runCatching { session().exec(big, 5_000L) }.exceptionOrNull()
        assertTrue("超长命令必须 REJECTED：err=$err", err is SandboxException && err.error.kind == SandboxError.Kind.REJECTED)
    }

    @Test
    fun timeoutKillsAndLeavesNoSentinelMinusOne() {
        val outcome = session().exec("sleep 30", 1_000L)
        assertTrue("超时必须标记 timedOut：$outcome", outcome.timedOut)
        assertNull("超时的退出码必须是 null（不再拿 -1 当哨兵，D4）：exitCode=${outcome.exitCode}", outcome.exitCode)
        assertTrue("超时也要有终态行：${outcome.terminalLine}", outcome.terminalLine.contains("强杀") || outcome.terminalLine.contains("超时"))
    }

    @Test
    fun busyGateRejectsSecondCommandWithExplicitReason() {
        val s = session()
        // 真实占用：起一个 sleep，然后并发第二个命令必须被 BUSY 拒绝
        val holder = ProcessBuilder("/bin/sh", "-c", "sleep 2").start()
        try {
            // 直接占住内部 busy 位（不通过 exec，精确模拟「上一个操作还在跑」）
            val field = ProotSession::class.java.getDeclaredField("currentProcess")
            field.isAccessible = true
            field.set(s, holder)
            val err = runCatching { s.exec("echo should-not-run", 5_000L) }.exceptionOrNull()
            assertTrue("第二个命令必须 BUSY：err=$err", err is SandboxException && err.error.kind == SandboxError.Kind.BUSY)
            assertTrue("拒绝原因必须说明上一个操作还在跑：${err?.message}", err!!.message!!.contains("上一个操作"))
        } finally {
            holder.destroyForcibly()
            holder.waitFor(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun outputOverCapIsDrainedNotDeadlocked() {
        // 输出远超 TAIL_CHARS：验证不卡死、流被排干（进程正常退出）
        val outcome = session().exec("seq 1 20000", 15_000L)
        assertEquals("大批量输出不许卡死也不许丢退出码：$outcome", 0, outcome.exitCode)
        assertTrue("尾巴只保最后一段：stdout 长度=${outcome.stdoutTail.length}", outcome.stdoutTail.length <= ProotSession.TAIL_CHARS + 100)
        assertTrue("尾部应含最后几行：", outcome.stdoutTail.contains("19999") || outcome.stdoutTail.contains("20000"))
    }

    @Test
    fun failedCommandCarriesExitCodeAndStderr() {
        val outcome = session().exec("echo boom >&2; exit 3", 10_000L)
        assertEquals("退出码要照实：$outcome", 3, outcome.exitCode)
        assertTrue("stderr 尾要含 boom：${outcome.stderrTail}", outcome.stderrTail.contains("boom"))
    }
}
