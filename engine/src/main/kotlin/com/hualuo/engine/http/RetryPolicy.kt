package com.hualuo.engine.http

/**
 * 「要不要重来一次」的决策层。
 *
 * 和 [HttpTaxonomy] 分开是有意的：**分类是事实，重发是花钱的决定**。
 * 旧仓真正的毛病是 524 连「瞬时失败」都没判上（直接落到通用失败里，于是既不归类、
 * 也不提示可以重试、更没有等一等再来），不是「本该自动重发」。
 * 对会花 token 的请求，自动重发是产品决定，不是工程默认 —— 所以这里做成显式开关，
 * 默认**关**，并把「为什么没替你重发」写进给出的原因里。
 *
 * 三条不许商量的红线：
 *  1) **收到过内容就不许盲重**（会重复内容 + 再花一次钱），要么从检查点续，要么让用户点重试；
 *  2) **上下文超限永不重发**（重发同一份内容只会再错一次，还是最贵的那种错）；
 *  3) **次数有上限**，且退避有顶 —— 不许无限重试把网关打死。
 */
class RetryPolicy(
    val maxAutomaticRetries: Int = 2,
    val backoffBaseMs: Long = 1_000L,
    val backoffMaxMs: Long = 30_000L,
    /** 会花钱的请求要不要自动重发。默认关：不拿用户的 token 赌运气。 */
    val retryOnCostlyRequests: Boolean = false,
) {

    init {
        require(maxAutomaticRetries >= 0) { "自动重试次数不能为负，当前=$maxAutomaticRetries" }
        require(backoffBaseMs >= 0) { "退避基数不能为负，当前=$backoffBaseMs" }
        require(backoffMaxMs >= backoffBaseMs) {
            "退避上限($backoffMaxMs)不能小于基数($backoffBaseMs)"
        }
    }

    /** 一次尝试结束时的观察结果。字段都是事实，不含判断。 */
    data class AttemptOutcome(
        val attempt: Int,
        val cost: RequestCost,
        /** 状态码；0 表示一次响应都没收到（连接层面就死了）。 */
        val status: Int,
        val body: String = "",
        val bytesReceived: Long = 0L,
        val retryAfterMs: Long? = null,
        /** 看门狗判定：连接没断，但已经没有字节了。 */
        val stalled: Boolean = false,
        val localFailure: FailureClass? = null,
    ) {
        init {
            require(attempt >= 1) { "attempt 从 1 开始，当前=$attempt" }
            require(bytesReceived >= 0L) { "收到的字节数不能为负：$bytesReceived" }
        }
    }

    /**
     * 按现在的收场决定下一步。
     * 只吃事实、不碰网络、不取系统时间 —— 所以整张决策表能在 JVM 里穷举测试。
     */
    fun decide(o: AttemptOutcome): RetryDecision {
        // 1. 用户自己取消的：不是失败，别重发，也别弹红徽标
        if (o.localFailure == FailureClass.Cancelled) {
            return RetryDecision.GiveUp(FailureClass.Cancelled, "这次是你自己取消的，不重发")
        }
        // 2. 超限优先于状态码：证据在 body 里，跟返回码无关
        if (HttpTaxonomy.isContextOverflow(o.body)) {
            return RetryDecision.GiveUp(
                FailureClass.ContextOverflow,
                "上下文超限：重发同一份内容只会再错一次，得删内容或开新会话",
            )
        }
        // 3. 成功收场
        if (o.status in 200..299 && !o.stalled && o.localFailure == null) {
            return RetryDecision.Done
        }
        val klass = when {
            o.stalled -> FailureClass.Stalled
            o.localFailure != null -> o.localFailure
            else -> HttpTaxonomy.classify(o.status, o.body)
        }
        val costly = o.cost != RequestCost.Free

        // 4. 已经收到过内容 —— 花钱请求绝不盲重（免费请求可续传，走下面的分支）
        if (costly && o.bytesReceived > 0L) {
            return RetryDecision.GiveUp(
                klass,
                "已经收到 ${o.bytesReceived} 字节：自动重发会重复内容并再花一次钱，" +
                    "要么从检查点续，要么把重试交给用户点",
            )
        }
        // 5. 连接还挂着但没字节了
        if (o.stalled) {
            return if (!costly) {
                plan(o, FailureClass.Stalled, "字节停滞，免费请求直接续传")
            } else {
                RetryDecision.GiveUp(
                    FailureClass.Stalled,
                    "连接没断但已经没有字节了（这次生成会花钱）：已停止等待，可一键重试",
                )
            }
        }
        // 6. 压根没连上 / DNS / TLS
        if (o.status == 0 || o.localFailure == FailureClass.NoConnection) {
            return if (!costly) plan(o, FailureClass.NoConnection, "没连上，重来一次")
            else gateCostly(o, FailureClass.NoConnection, "一次响应都没收到")
        }
        // 7. 用法错误：4xx 里除了 408/429 都不该重发
        if (klass == FailureClass.Client) {
            return RetryDecision.GiveUp(klass, "服务端说这份请求本身不对（${o.status}），重发只会再错一次")
        }
        // 8. 限流：有 Retry-After 就照它等，且不许无限等
        if (klass == FailureClass.RateLimited) {
            val wait = o.retryAfterMs?.coerceAtMost(backoffMaxMs) ?: backoffMs(o.attempt)
            return if (o.attempt > maxAutomaticRetries) {
                RetryDecision.GiveUp(klass, "额度/频率受限，已自动等过 $maxAutomaticRetries 次仍不行")
            } else {
                RetryDecision.Retry(o.attempt + 1, wait, "受限流，等 ${wait}ms 再来")
            }
        }
        // 9. 网关类：瞬时表里的可以重来；只给幂等请求的表要先问代价
        val transient = HttpTaxonomy.isTransient(o.status)
        if (!transient) {
            if (HttpTaxonomy.isIdempotentOnly(o.status)) {
                return if (!costly) plan(o, klass, "状态码 ${o.status} 只对免费请求重来")
                else RetryDecision.GiveUp(klass, "状态码 ${o.status} 对会花钱的请求不自动重发")
            }
            return RetryDecision.GiveUp(klass, "状态码 ${o.status} 不属于可重来的那一类")
        }
        if (costly) {
            return gateCostly(o, klass, "网关类失败（${o.status}）")
        }
        return plan(o, klass, "网关类失败（${o.status}）")
    }

    private fun gateCostly(o: AttemptOutcome, klass: FailureClass, prefix: String): RetryDecision =
        if (!retryOnCostlyRequests) {
            RetryDecision.GiveUp(
                klass,
                "$prefix：这次请求会花 token，默认不替你自动重发（原因见类别）；可以直接点重试",
            )
        } else {
            plan(o, klass, prefix)
        }

    /** 统一处理「还允不允许再来一次 + 等多久」。 */
    private fun plan(o: AttemptOutcome, klass: FailureClass, reason: String): RetryDecision {
        val used = o.attempt - 1
        if (used >= maxAutomaticRetries) {
            return RetryDecision.GiveUp(klass, "$reason：已自动重试 $used 次仍失败，停手")
        }
        val wait = backoffMs(o.attempt)
        return RetryDecision.Retry(
            attempt = o.attempt + 1,
            waitMs = wait,
            reason = "$reason：第 ${o.attempt + 1} 次尝试，等 ${wait}ms",
        )
    }

    /** 指数退避，带上限；不用移位是为了在 attempt 很大时也不溢出。 */
    fun backoffMs(attempt: Int): Long {
        var wait = backoffBaseMs
        var step = 1
        while (step < attempt && wait < backoffMaxMs) {
            wait = (wait * 2).coerceAtMost(backoffMaxMs)
            step += 1
        }
        return wait.coerceAtMost(backoffMaxMs)
    }
}

/** 这次请求重发一次的代价。说不清就按最贵的处理。 */
enum class RequestCost {
    /** 重发免费且无副作用：GET、列表、可续传的下载。 */
    Free,

    /** 会真花钱或改状态：chat 补全、上传、发消息。 */
    Costly,

    /** 调用方没说 —— 一律按 [Costly] 处理。 */
    Unknown,
}

/** [RetryPolicy.decide] 的三种收场。 */
sealed class RetryDecision {
    /** 这次结果可以直接用，别重来。 */
    object Done : RetryDecision()

    /** 等 [waitMs] 毫秒后发第 [attempt] 次。 */
    data class Retry(val attempt: Int, val waitMs: Long, val reason: String) : RetryDecision()

    /** 不再自动重来：把 [reason] 原样给用户看，别只报一句「请求失败」。 */
    data class GiveUp(val failure: FailureClass, val reason: String) : RetryDecision()
}
