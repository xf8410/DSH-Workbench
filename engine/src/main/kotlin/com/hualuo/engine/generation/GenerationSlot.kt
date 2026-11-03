package com.hualuo.engine.generation

/** 能被取消的东西（一次 HTTP 流、一个协程 Job……）。本模块不认识协程库，取消动作由接线方实现。 */
fun interface Cancellable {
    fun cancel()
}

/** [GenerationSlot.stop] 的结果：原来有没有人、腾出的槽、取消成没成、取消时炸了什么。 */
data class StopOutcome(
    val hadHolder: Boolean,
    val token: Long,
    val cancelled: Boolean,
    val cancelError: String?,
)

/**
 * [GenerationSlot.runGuarded] 的结果。
 *
 * [stranded] 是给界面「出声」用的：我想放槽却发现槽已经不归我了 —— 这不算错误，
 * 但绝不能静默，因为它意味着有一次生成被顶替过，用户看到的「还在转圈」可能就是它留下的。
 */
data class GuardedResult<out T>(
    val value: T?,
    val failure: Exception?,
    val released: Boolean,
    val stranded: Boolean,
)

/**
 * 一次生成占的槽。这个类存在的唯一理由就是旧 App 那句「生成失败我每次都要重新打开软件」：
 * 旧仓里协程卡在永久读上、或者令牌错配后没人放槽，而槽只活在内存里，只有杀进程能清。
 *
 * 三条硬规矩，每条都有测试盯着：
 *  1) **同一时刻只有一个持有者**：[tryBegin] 拿不到就返回 null，调用方必须排队，
 *     不许两条生成并发改同一棵消息树（旧仓 G2–G10 那批串线事故的形状）；
 *  2) **放槽令牌化**：[end] 只认自己那份令牌；被 [stop] 或下一次生成顶替过的旧协程，
 *     不许把新主人的槽放掉；
 *  3) **任何收场都必须放槽**：[runGuarded] 保证正常返回、抛异常、被顶替三种结局都走一次放槽，
 *     放不成时 [GuardedResult.stranded] 为真 —— 绝不留下「界面还在转圈、其实早就没人干活」的空气泡。
 *
 * 线程模型：全部方法在 [lock] 下同步，可以跨线程调用；但「该排队还是该并发」这类决策仍归调用方。
 * 干活本体（[runGuarded] 的 block）在锁**外面**跑，槽只做占与放 —— 绝不在持锁时等网络。
 */
class GenerationSlot(
    private val watchdog: IdleWatchdog? = null,
) {

    private val lock = Any()
    private var issuedToken: Long = 0L
    private var holder: Long? = null
    private var running: Cancellable? = null

    /** 空槽就占下它；拿不到返回 null（调用方必须转为排队，不许硬闯）。 */
    fun tryBegin(cancellable: Cancellable? = null): Long? = synchronized(lock) {
        if (holder != null) return null
        issuedToken += 1
        holder = issuedToken
        running = cancellable
        watchdog?.restart()
        issuedToken
    }

    /** 当前持有者的令牌；没人在生成时为 null。 */
    fun currentToken(): Long? = synchronized(lock) { holder }

    fun isHolding(): Boolean = synchronized(lock) { holder != null }

    /** 换一条流的取消句柄（比如重定向后换了 Call），令牌不变。 */
    fun attach(cancellable: Cancellable?) {
        synchronized(lock) { running = cancellable }
    }

    /** 令牌相符才放槽；返回 false 表示「我已经不是主人了」，槽不归我管。 */
    fun end(claim: Long): Boolean = synchronized(lock) {
        if (holder != claim) return false
        holder = null
        running = null
        true
    }

    /**
     * 用户按停止，或看门狗判定卡死后由调用方收尾：取消在跑的活、令牌往前推、腾出槽。
     * 之后再回来的旧回调会因令牌失效被 [end] 拒掉，不会误伤下一次生成。
     * 幂等：没人持有时也只是推一下令牌，不会抛。
     *
     * 取消动作抛异常也**照样腾槽**：旧仓那种「取消失败就一直挂着转圈」是这条测试堵住的。
     */
    fun stop(): StopOutcome = synchronized(lock) {
        val previous = holder
        val toCancel = running
        issuedToken += 1
        holder = null
        running = null
        var error: String? = null
        var cancelled = false
        if (toCancel != null) {
            cancelled = true
            try {
                toCancel.cancel()
            } catch (e: Exception) {
                error = "${e.javaClass.simpleName}: ${e.message ?: "（无消息）"}"
            }
        }
        StopOutcome(
            hadHolder = previous != null,
            token = issuedToken,
            cancelled = cancelled,
            cancelError = error,
        )
    }

    /** 收到字节：转给看门狗归零。没装看门狗时是空操作。 */
    fun beat() {
        synchronized(lock) { watchdog?.beat() }
    }

    /**
     * 是否已判卡死；没人在生成、或没装看门狗时永远 false。
     * 写成纯表达式（不在 synchronized 里 return）：整块都是 return 的 lambda 类型推不出来，不赌。
     */
    fun stalled(nowMs: Long? = null): Boolean = synchronized(lock) {
        val dog = watchdog
        if (holder == null || dog == null) false
        else if (nowMs == null) dog.stalled() else dog.stalled(nowMs)
    }

    /** 当前静默了多久（没人在生成时返回 0）。 */
    fun idleMs(nowMs: Long? = null): Long = synchronized(lock) {
        val dog = watchdog
        if (holder == null || dog == null) 0L
        else if (nowMs == null) dog.idleMs() else dog.idleMs(nowMs)
    }

    /**
     * 占着槽跑一段活，**不管怎么收场都放一次槽**。
     * 异常不吞也不就地消化：原样放进 [GuardedResult.failure]，由调用方决定重试、报用户还是存检查点。
     * 只捕 Exception：`Error`（OOM 之类）不在本层掩饰，直接往上抛。
     *
     * **取消归谁**：本方法不替你取消底层流 —— 槽只管占与放，谁开的流谁负责在 catch 里
     * `call.cancel()`（接线那批必须做到，否则会漏一条半死的连接到 GC 为止；[stop] 是唯一
     * 由槽代劳取消的入口，因为它发生在「人已经不在等这条流」的时刻）。
     */
    fun <T> runGuarded(claim: Long, block: () -> T): GuardedResult<T> {
        var value: T? = null
        var failure: Exception? = null
        try {
            value = block()
        } catch (e: Exception) {
            failure = e
        }
        val released = end(claim)
        return GuardedResult(
            value = value,
            failure = failure,
            released = released,
            stranded = !released,
        )
    }
}
