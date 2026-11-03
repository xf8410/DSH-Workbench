package com.hualuo.engine.sandbox

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * proot 会话：装配命令、跑命令、收结果（修复 D4 busy 哨兵 + L2 输出封顶 + L3 参数拒绝）。
 *
 * 与旧实现的关键差别（docs/SANDBOX-LOGIC 修复方案第 4 条）：
 *  - busy 时**明确拒绝**并说明「上一个操作还在跑」，不再静默 return（旧实现直接吞掉请求）；
 *  - 退出码不再用 -1 当「失败」哨兵：超时强杀 = [ExecOutcome.timedOut]=true 且退出码为空；
 *  - 输出有上限但**必须排干**（子进程把管道写满就死锁的经典坑），封顶不存但读走不少报；
 *  - 每次执行结束必落一行终态（TOOL-CONTRACTS 契约二.3），不许静默结束。
 *
 * proot 参数对齐官方版（--link2symlink / --kill-on-exit / -0 / -L），因为 Alpine rootfs
 * 里的绝对符号链接（/bin/sh 之类）依赖 link2symlink 才能在 Android 私有目录里落地。
 * 官方版把这些参数散在 793 行大文件里，这里只做装配这一件事。
 */
class ProotSession(
    private val rootfsDir: File,
    private val sharedDir: File? = null,
    /** 额外绑定挂载（host 目录 -> guest 路径，guest 可带 :ro 标记）。apk 本地包安装用。 */
    internal val bindMounts: List<Pair<File, String>> = emptyList(),
    private val prootBinary: String = "proot",
    private val launcher: Launcher = Launcher { cmd, workdir ->
        ProcessBuilder(cmd).apply { workdir?.let { directory(it) } }.start()
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun interface Launcher {
        fun start(command: List<String>, workdir: File?): Process
    }

    data class ExecOutcome(
        val exitCode: Int?,
        val timedOut: Boolean,
        val stdoutTail: String,
        val stderrTail: String,
        val drainedStdoutBytes: Long,
        val drainedStderrBytes: Long,
        val elapsedMs: Long,
        val terminalLine: String,
    )

    @Volatile
    private var currentProcess: Process? = null

    @Volatile
    private var currentDesc: String? = null

    val isBusy: Boolean
        get() = currentProcess?.isAlive == true

    /** 上一条还在跑时的说明文字（拒绝时带给调用方，不静默）。 */
    fun busyReason(): String =
        "上一个操作还在跑（${currentDesc ?: "未知"}），沙盒同一时刻只跑一条命令；等它结束再试"

    /**
     * 跑一条 shell 命令。
     * 修复 L3：空命令 / 超长命令在进门就被拒，给明确 kind=REJECTED，不带模糊往下走。
     */
    fun exec(command: String, timeoutMs: Long = 60_000L): ExecOutcome {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            throw SandboxError(SandboxError.Kind.REJECTED, stderrSnippet = "命令为空：要么给命令，要么别调").toException()
        }
        if (trimmed.length > MAX_COMMAND_CHARS) {
            throw SandboxError(
                SandboxError.Kind.REJECTED,
                stderrSnippet = "命令过长（${trimmed.length} 字符 > 上限 $MAX_COMMAND_CHARS）；拆成多条跑",
            ).toException()
        }
        if (timeoutMs <= 0L) {
            throw SandboxError(SandboxError.Kind.REJECTED, stderrSnippet = "超时时间必须大于零（现在 $timeoutMs）").toException()
        }
        if (isBusy) {
            throw SandboxError(SandboxError.Kind.BUSY, stderrSnippet = busyReason()).toException()
        }

        val cmd = buildCommand(trimmed)
        val started = clock()
        val process = launcher.start(cmd, null)
        currentProcess = process
        currentDesc = trimmed.take(60)

        val outTail = TailBuffer(TAIL_CHARS)
        val errTail = TailBuffer(TAIL_CHARS)
        val outDrain = drain(process.inputStream, outTail)
        val errDrain = drain(process.errorStream, errTail)

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        val timedOut = !finished
        if (timedOut) {
            process.destroyForcibly()
        }
        val exitCode = runCatching { process.exitValue() }.getOrNull()
        outDrain.join(5_000)
        errDrain.join(5_000)
        currentProcess = null
        currentDesc = null
        val elapsed = clock() - started

        val terminal = if (timedOut) {
            "终态：超时强杀（超过 $timeoutMs 毫秒）；输出已排干 ${outTail.bytes + errTail.bytes} 字节"
        } else {
            "终态：exit=$exitCode 耗时=${elapsed}毫秒"
        }
        return ExecOutcome(
            exitCode = exitCode,
            timedOut = timedOut,
            stdoutTail = outTail.text,
            stderrTail = errTail.text,
            drainedStdoutBytes = outTail.bytes,
            drainedStderrBytes = errTail.bytes,
            elapsedMs = elapsed,
            terminalLine = terminal,
        )
    }

    /** proot 命令装配：rootfs + 标准绑定 + link2symlink + sh -c。 */
    fun buildCommand(shellCommand: String): List<String> {
        val cmd = mutableListOf(
            prootBinary,
            "-r", rootfsDir.absolutePath,
            "-0",
            "--link2symlink",
            "--kill-on-exit",
            "-L",
            "-b", "/proc",
            "-b", "/dev",
            "-b", "/sys",
        )
        sharedDir?.let {
            it.mkdirs()
            cmd.add("-b")
            cmd.add("${it.absolutePath}:/shared")
        }
        for ((host, guest) in bindMounts) {
            host.mkdirs()
            // 绑定写法支持 host:guest 与 host:guest:ro（ro 只是标记，proot 无只读绑定，靠命令侧约束）
            cmd.add("-b")
            cmd.add("${host.absolutePath}:$guest")
        }
        cmd.addAll(listOf("/bin/sh", "-c", shellCommand))
        return cmd
    }

    /** 流式排干 + 环形尾缓冲；封顶不存但字节照读不少报（防死锁 + 可对账）。 */
    private fun drain(input: InputStream, tail: TailBuffer): Thread {
        val t = Thread {
            val buf = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        tail.append(buf, n)
                    }
                }
            } catch (_: java.io.IOException) {
                // 进程被强杀时流会断，读线程安静收场即可
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }

    /** 环形尾巴：只保最后 N 字符，但多少字节进过这里是有账的（[bytes]）。 */
    private class TailBuffer(private val capacity: Int) {
        private val sb = StringBuilder(capacity + 2)
        var bytes: Long = 0L
            private set

        fun append(buf: ByteArray, len: Int) {
            bytes += len
            val text = String(buf, 0, len, Charsets.UTF_8)
            sb.append(text)
            if (sb.length > capacity) sb.delete(0, sb.length - capacity)
        }

        val text: String get() = sb.toString()
    }

    /** 仅供测试：占住 busy 门一段时间的睡眠进程（验证并发拒绝用）。 */
    internal fun holdForBusyTest(ms: Long): Process {
        val p = launcher.start(listOf("/bin/sh", "-c", "sleep ${ms / 1000 + 1}"), null)
        currentProcess = p
        currentDesc = "busy-test"
        return p
    }


    companion object {
        const val MAX_COMMAND_CHARS = 200_000
        const val TAIL_CHARS = 8_000
    }
}
