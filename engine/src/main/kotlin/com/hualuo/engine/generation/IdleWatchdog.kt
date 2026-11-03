package com.hualuo.engine.generation

/**
 * 「静默多久算卡死」的判定器 —— 只量**空闲**，不量总时长。
 *
 * 这条设计直接对着旧仓那次翻车：`b412dfb3` 为了不让长思考被 30 分钟读超时误杀，
 * 把读超时全部归零（OkHttp 的 0 = 永不超时）。后果是死连接从此**永不报错**：
 * 协程永远阻塞在一次读上，生成槽没人放，只能杀掉 App 进程。
 *
 * 两个极端都不对，所以这里：
 *  - 每收到一个字节就 [beat]，计时归零 —— 传三小时的游戏数据，只要字节在流，永远不判卡死；
 *  - 连续 [limitMs] 毫秒一个字节都没有 —— 判卡死，调用方必须放手（释放槽、报错、走续传）。
 *
 * 时钟可注入，「四小时持续有字节」和「60 秒零字节」都能在测试里一瞬间跑完。
 * [UNLIMITED] 保留了旧行为作为**显式可选项**：谁要用，谁就得自己保证还有别的放手办法。
 */
class IdleWatchdog(
    val limitMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    init {
        require(limitMs >= 0L) { "空闲上限不能是负数，当前=$limitMs（0=不限）" }
    }

    private var lastBeatMs: Long = clock()

    /** 收到字节：静默计时归零。 */
    fun beat() {
        lastBeatMs = clock()
    }

    /** 从头计一次（新连接建立、断点续传成功之后用，别拿旧连接的计时器诬陷新连接）。 */
    fun restart() {
        lastBeatMs = clock()
    }

    /** 已经静默了多少毫秒（时钟倒退时按 0 算，不返回负数）。 */
    fun idleMs(nowMs: Long = clock()): Long = (nowMs - lastBeatMs).coerceAtLeast(0L)

    /** 是否判为卡死；[UNLIMITED] 模式永远返回 false。 */
    fun stalled(nowMs: Long = clock()): Boolean = limitMs > 0L && idleMs(nowMs) >= limitMs

    /** 还剩多少余地；[UNLIMITED] 模式返回 [Long.MAX_VALUE]。给「快到头了先提醒」用。 */
    fun remainingMs(nowMs: Long = clock()): Long =
        if (limitMs <= 0L) Long.MAX_VALUE else (limitMs - idleMs(nowMs)).coerceAtLeast(0L)

    companion object {
        /** 不限空闲（旧行为）。 */
        const val UNLIMITED = 0L

        /** 生成流默认档位：容忍 5 分钟静默（大模型 prefill/深度思考可以几分钟不出字）。 */
        const val GENERATION_IDLE_MS = 300_000L

        /** 大文件传输默认档位：字节停滞 60 秒就判断线，走 Range 续传或按卷重试。 */
        const val TRANSFER_IDLE_MS = 60_000L
    }
}
