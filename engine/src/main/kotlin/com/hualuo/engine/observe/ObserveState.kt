package com.hualuo.engine.observe

/**
 * SO 观测桥连接状态机（560 清单 364-370 六态 + 373-379 超时/单并发/退避/熔断/冷却）。
 *
 * 纯 JVM、无 Android 依赖、无时钟隐藏——时间一律从外面喂（[nowMs]），
 * 测试可以拨表。线程安全：所有可变状态锁在同一把上（状态机是唯一的写入口，
 * 工具执行与探测都先问它要许可）。
 *
 * 单并发（375）：对端是嵌入式 SO，一人一格；[tryBegin] 抢不到就 false。
 * 退避冷却（377/379）：连续失败翻倍退避，上限 5 分钟；OVERLOADED 从 60s 起。
 * 熔断（378）：冷却期内工具直接拒——不是报错升级，是"现在别敲"的礼貌。
 */
class ObserveState(private val nowMs: () -> Long = { System.currentTimeMillis() }) {

    enum class Link {
        DISCONNECTED, CONNECTING, READY, DEGRADED, OVERLOADED, INCOMPATIBLE;

        /** 中文显示名（UI 直接用）。 */
        val label: String
            get() = when (this) {
                DISCONNECTED -> "未连接"
                CONNECTING -> "探测中"
                READY -> "已就绪"
                DEGRADED -> "降级"
                OVERLOADED -> "对端过载"
                INCOMPATIBLE -> "疑似非 hlpatch"
            }
    }

    @Volatile private var current: Link = Link.DISCONNECTED
    private var busy = false
    private var failureStreak = 0
    private var cooldownUntil = 0L
    /** 最后一次探测/调用的说明（人话账）。 */
    @Volatile var lastNote: String = ""
        private set

    val link: Link get() = current

    /** 冷却剩余毫秒；0 = 没在冷却。 */
    fun cooldownRemainingMs(): Long = (cooldownUntil - nowMs()).coerceAtLeast(0L)

    /**
     * 探测前的许可：不在冷却且没人在探测，才发 [Link.CONNECTING]。
     * 返回 false 时 [reasonRef]（可空）带上拒绝原因。
     */
    @Synchronized
    fun tryBeginProbe(): Boolean {
        if (busy) {
            lastNote = "已有一次探测/请求在进行"
            return false
        }
        val remain = cooldownRemainingMs()
        if (remain > 0) {
            lastNote = "冷却中，还剩 ${remain / 1000}s（连败 $failureStreak 次后的退避）"
            return false
        }
        busy = true
        current = Link.CONNECTING
        return true
    }

    /**
     * 用探测事实账收场（判定规则见 [ObserveClient.probe] 注）。
     * 必须与 [tryBeginProbe] 成对调用（无论结果如何都要 [endBusy]）。
     */
    @Synchronized
    fun settleProbe(healthOk: Boolean, statusOk: Boolean, healthStatus: Int?, note: String) {
        endBusy()
        when {
            healthStatus == 503 || healthStatus == 429 -> {
                current = Link.OVERLOADED
                penalize(baseMs = 60_000)
                lastNote = "对端忙（HTTP $healthStatus），进冷却退避"
            }
            !healthOk -> {
                current = Link.DISCONNECTED
                penalize()
                lastNote = note
            }
            statusOk -> {
                current = Link.READY
                clearStreak()
                lastNote = "探测通过：health + status 都通"
            }
            else -> {
                current = Link.DEGRADED
                clearStreak()
                lastNote = note
            }
        }
    }

    /** 单次工具调用的许可：READY/DEGRADED 才放行（冷却/忙都拒）。 */
    @Synchronized
    fun tryBeginCall(): Boolean {
        if (busy) {
            lastNote = "已有一次探测/请求在进行（单并发）"
            return false
        }
        val remain = cooldownRemainingMs()
        if (remain > 0) {
            lastNote = "冷却中，还剩 ${remain / 1000}s"
            return false
        }
        if (current == Link.DISCONNECTED || current == Link.OVERLOADED || current == Link.INCOMPATIBLE) {
            lastNote = "桥不在可调用态（$current），先探测"
            return false
        }
        busy = true
        return true
    }

    /** 工具调用收场：2xx 算顺，失败也只记一笔（不打断 READY——偶发失败别把桥判死）。 */
    @Synchronized
    fun settleCall(ok: Boolean, note: String) {
        endBusy()
        lastNote = note
        if (!ok) failureStreak++
    }

    @Synchronized
    fun endBusy() {
        busy = false
    }

    /** 连败翻倍退避：5s 起步，翻倍，封顶 5 分钟。 */
    private fun penalize(baseMs: Long = 0) {
        failureStreak++
        val backoff = if (baseMs > 0) baseMs else 5_000L shl (failureStreak - 1).coerceAtMost(9)
        cooldownUntil = nowMs() + backoff.coerceAtMost(300_000L)
    }

    private fun clearStreak() {
        failureStreak = 0
        cooldownUntil = 0L
    }
}
