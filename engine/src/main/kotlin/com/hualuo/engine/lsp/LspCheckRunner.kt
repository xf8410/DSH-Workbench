package com.hualuo.engine.lsp

import com.hualuo.engine.io.sanitizeForLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 执行侧第一件：把一条检查命令真跑起来，收满输出、盯死时间与体积，
 * 然后把输出交给 [DiagnosticParser] 变成诊断账。这就是「改完码当场出诊断」的引擎炉子。
 *
 * 为什么执行与解析必须分开：本件只负责把原始输出干净地拿回来（谁跑的、跑了多久、
 * 收了多少、有没有被砍）；认得出几条诊断由解析器说。两边各自能单测，谁也不替谁下结论。
 *
 * 三条硬纪律（全是旧账换来的）：
 *  1) **时间有上限**：到点强杀，收场照实报 [CheckOutcome.timedOut]。
 *     旧 Agora 把读超时归零导致生成永久堵死只能杀进程——这里反过来，谁跑命令谁带表。
 *  2) **输出有上限但必须排干**：子进程把管道缓冲写满就再也不动了（经典死锁），
 *     所以读线程从头读到尾；只是超了封顶的字节不再存。收了总共多少字节照实报，
 *     截断的必须说出来（[CheckOutcome.outputTruncated]），不许拿半截当完整。
 *  3) **收场点名**：超时、退出码、截断、耗时一个不少。超时后退出码是强杀的产物，
 *     不是业务码——[CheckOutcome.exitCode] 的 KDoc 里写死了这一点。
 *
 * 执行走 [CheckLaunch] 缝隙注入：真实现是 [processCheckLaunch]（ProcessBuilder），
 * 测试注入假实现，超时与截断这类难复现的分支就能纯 JVM 验证。
 */
data class CheckOutcome(
    /**
     * 进程退出码；[timedOut] 为真时它是强杀的产物（多数内核下是 137 附近），**不是业务退出码**，
     * 调用方先看超时标记再看它。进程连退出码都没来得及给（罕见的立即失败）时为 null。
     */
    val exitCode: Int?,
    /** stdout 与 stderr 合并后的文本，超封顶的部分已被砍（砍了会由 outputTruncated 说）。 */
    val output: String,
    /** 等超时时间仍未退出、已被强杀 = true。 */
    val timedOut: Boolean,
    /** 输出超封顶被砍 = true（字节账见 sinkBytes）。 */
    val outputTruncated: Boolean,
    /** 实际从进程读走的总字节数（不因封顶而少报——它证明流被排干了）。 */
    val sinkBytes: Long,
    /** 从起进程到收场的墙钟毫秒数。 */
    val durationMs: Long,
)

/** 执行缝隙：真实现见 [processCheckLaunch]；测试注入假实现以复现超时、截断等分支。 */
fun interface CheckLaunch {
    fun launch(command: List<String>, workDir: File, timeoutMs: Long, maxOutputBytes: Int): CheckOutcome
}

/** 命令没跑起来的原因（参数不全、目录不在、起进程失败、等待被中断）。消息中文、带出路。 */
class CheckReject(reason: String) : Exception(reason)

/**
 * 真实现：ProcessBuilder 起进程，stderr 并入 stdout（编译器喜欢分两路，合并后一次读完），
 * 读线程持续排干、超封顶不存；到点强杀；退出码与耗时照实报。
 */
val processCheckLaunch: CheckLaunch = CheckLaunch { command, workDir, timeoutMs, maxOutputBytes ->
    if (command.isEmpty()) throw CheckReject("命令是空的（先把命令行组装出来再跑）")
    if (!workDir.isDirectory) throw CheckReject("工作目录不在：" + sanitizeForLog(workDir.path))
    if (timeoutMs <= 0L) throw CheckReject("超时时间必须大于零（现在是 $timeoutMs 毫秒）")
    if (maxOutputBytes <= 0) throw CheckReject("输出上限必须大于零（现在是 $maxOutputBytes 字节）")

    val builder = ProcessBuilder(command)
    builder.directory(workDir)
    builder.redirectErrorStream(true)
    val startedNanos = System.nanoTime()
    val process = try {
        builder.start()
    } catch (e: IOException) {
        throw CheckReject("命令起不来（多半是路径不对或没有执行权限）：" + sanitizeForLog(e.message ?: "IO 错"))
    }

    var totalBytes = 0L
    val kept = ByteArrayOutputStream()
    val reader = Thread {
        runCatching {
            process.inputStream.use { input ->
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    totalBytes += n
                    val room = maxOutputBytes - kept.size()
                    if (room > 0) kept.write(chunk, 0, minOf(n, room))
                }
            }
        }
    }
    reader.isDaemon = true
    reader.start()

    val finished = try {
        process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        process.destroyForcibly()
        throw CheckReject("等命令时被中断，进程已强杀")
    }
    var timedOut = false
    if (!finished) {
        timedOut = true
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
    }
    reader.join(5000)
    val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000
    val exitCode = try {
        process.exitValue()
    } catch (e: IllegalThreadStateException) {
        null
    }
    CheckOutcome(
        exitCode = exitCode,
        output = kept.toString("UTF-8"),
        timedOut = timedOut,
        outputTruncated = totalBytes > maxOutputBytes,
        sinkBytes = totalBytes,
        durationMs = elapsedMs,
    )
}

/** 一次检查的完整账：解析结论 + 原始执行回执（超时与截断的判词在回执里，界面两个都要摆）。 */
data class CheckResult(val report: DiagnosticReport, val outcome: CheckOutcome) {
    /** 这次检查到底有没有产出「可判的结论」——超时或零输出时调用方要先说清，别拿空列表当「没问题」。 */
    val judgeable: Boolean get() = !outcome.timedOut
}

/**
 * 门面：跑一条命令，把输出喂给解析器，两样账一起交。
 *
 * 参数纪律：
 *  - [command] 必须是组装好的完整命令行（含可执行文件路径）；空命令行当场拒；
 *  - [parserTool] 是给解析器的工具族名（gcc / javac / kotlinc / rustc / go / tsc），本件不猜；
 *  - 超时与输出上限在构造时钉死；要不同档位就建不同实例（比如带编译的档位比 lint 长）。
 */
class LspCheckRunner(
    private val launch: CheckLaunch = processCheckLaunch,
    private val timeoutMs: Long = 60_000L,
    private val maxOutputBytes: Int = 512 * 1024,
) {

    init {
        require(timeoutMs > 0L) { "超时时间必须大于零，现在是 $timeoutMs 毫秒" }
        require(maxOutputBytes > 0) { "输出上限必须大于零，现在是 $maxOutputBytes 字节" }
    }

    /** 跑一次检查。失败抛 [CheckReject]（命令没跑起来）；跑起来了但结果坏不抛，全在账里。 */
    fun run(command: List<String>, workDir: File, parserTool: String): CheckResult {
        val outcome = launch.launch(command, workDir, timeoutMs, maxOutputBytes)
        val report = DiagnosticParser.parse(outcome.output, parserTool, outcome.exitCode)
        return CheckResult(report, outcome)
    }
}
