package com.hualuo.engine.http

/**
 * 失败分类。**光看 HTTP 状态码判不了「上下文超限」**：网关经常拿 502 表示各种不相干的
 * upstream 故障，也常把真正的超限包在 400 里。所以只有响应体里的证据能定这个性。
 *
 * 这套正则从旧仓 `HttpGenerationErrorPolicy` 移植而来，并把旧仓**漏掉的那半截重试白名单**
 * 一起补上：见 [TRANSIENT_STATUSES] 的注释。
 *
 * 本文件只做「这是什么失败」；「要不要重来一次」在 [RetryPolicy] —— 分层的原因是
 * 重试会花真钱：一条 chat 请求在 524 上盲重一次，就是再烧一遍 token。
 */
object HttpTaxonomy {

    /**
     * 各家写法混着来（空格、下划线、驼峰混排），一条规则不够。
     *
     * 全部规则都**要求词之间有分隔符**（空格或下划线），所以紧凑驼峰（`RequestTooLarge`）
     * 与纯中文描述都不在能力圈内 —— 这两条边界由测试钉着，别以为已经覆盖所有表述。
     */
    private val contextPatterns = listOf(
        Regex("maximum\\s+context\\s+length", RegexOption.IGNORE_CASE),
        Regex("context\\s+(length|window)\\s+(is\\s+)?(exceeded|overflow|too\\s+(large|long))", RegexOption.IGNORE_CASE),
        Regex("exceed(s|ed)?[^\\n]{0,80}context[^\\n]{0,40}(length|window|limit)", RegexOption.IGNORE_CASE),
        Regex("too\\s+many\\s+(input\\s+|prompt\\s+)?tokens", RegexOption.IGNORE_CASE),
        Regex("(input|prompt)[^\\n]{0,40}(too\\s+long|token\\s+limit)", RegexOption.IGNORE_CASE),
        Regex("token[^\\n]{0,40}(budget|limit)[^\\n]{0,40}(exceeded|overflow)", RegexOption.IGNORE_CASE),
        // OpenAI 风格的下划线错误码：context_length_exceeded 等
        Regex("context[_\\s]+length[_\\s]+(is[_\\s]+)?(exceeded|too[_\\s]+long|overflow)", RegexOption.IGNORE_CASE),
        Regex("context[_\\s]+window[_\\s]+(exceeded|overflow|too[_\\s]+(long|large))", RegexOption.IGNORE_CASE),
        Regex("maximum[_\\s]+context[_\\s]+(length|size|tokens?)", RegexOption.IGNORE_CASE),
        Regex("prompt[_\\s]+is[_\\s]+too[_\\s]+long", RegexOption.IGNORE_CASE),
        Regex("(input|prompt)[_\\s]+token[_\\s]*(count)?[_\\s]*exceeds", RegexOption.IGNORE_CASE),
        // 旧仓这里只写了词根 exceed，真样本（"…131072 input tokens, exceeds the maximum
        // number of input tokens"）用的是第三人称单数 —— 旧仓那条测试是靠 502 的兜底归类
        // 蒙对的，规则本身没认出来。补上三种时态，别再靠兜底装看不见。
        Regex(
            "(exceed|exceeds|exceeded|greater|more)[_\\s]+(than[_\\s]+)?(the[_\\s]+)?(maximum|max)[_\\s]+(number[_\\s]+of[_\\s]+)?(input[_\\s]+)?tokens",
            RegexOption.IGNORE_CASE,
        ),
        Regex("max[_\\s]+tokens?[_\\s]+exceed", RegexOption.IGNORE_CASE),
        Regex("request[_\\s]+too[_\\s]+large", RegexOption.IGNORE_CASE),
    )

    /**
     * 传输层可以重来一次的状态码。
     *
     * 旧仓的白名单只有 `{429, 502, 503, 504}`，**Cloudflare 全家一个都没有**：
     * 524（网关等源站等到超时）恰恰是「模型想了很久、中间设备把连接掐了」最常见的那个码，
     * 漏掉它的结果是既不归类也不提示，用户只看到一次莫名失败。
     * 这里补齐 520~526 与代理侧的 598/599。
     */
    val TRANSIENT_STATUSES: Set<Int> = setOf(408, 429, 502, 503, 504, 520, 521, 522, 523, 524, 525, 526, 598, 599)

    /**
     * 只有「这次请求没花过钱、且重发安全」才值得重来的状态码。
     * 500/501 多半是服务端代码有确定性 bug，盲重试只是放大噪声；507/509 是盘/带宽额度，
     * 等一会儿或换地方才有意义。
     */
    val IDEMPOTENT_ONLY_STATUSES: Set<Int> = setOf(500, 501, 507, 509)

    /** 给用户提供解释时最多带多少字的原始响应（网关错误页能有大几十 KB 的 HTML）。 */
    const val MAX_PROVIDER_MESSAGE_CHARS = 4_000

    /** 响应体里有没有「上下文超限」的证据。空响应一律 false（空体不是超限，是没内容）。 */
    fun isContextOverflow(body: String): Boolean {
        if (body.isBlank()) return false
        return contextPatterns.any { it.containsMatchIn(body) }
    }

    fun isTransient(status: Int): Boolean = status in TRANSIENT_STATUSES

    fun isIdempotentOnly(status: Int): Boolean = status in IDEMPOTENT_ONLY_STATUSES

    /** 把状态码与响应体合成一个失败类别。超限优先于状态码：400 也可能是超限。 */
    fun classify(status: Int, body: String): FailureClass {
        if (isContextOverflow(body)) return FailureClass.ContextOverflow
        return when {
            status == 429 -> FailureClass.RateLimited
            status == 408 || status == 504 || status == 524 -> FailureClass.GatewayTimeout
            status in TRANSIENT_STATUSES || status in IDEMPOTENT_ONLY_STATUSES -> FailureClass.Gateway
            status in 400..499 -> FailureClass.Client
            status in 500..599 -> FailureClass.Gateway
            else -> FailureClass.Unknown
        }
    }

    /**
     * 截断供应商原文：只砍尾巴，**不往正文里塞任何标记**。
     * 这条是家规 —— 旧仓 `MessagePersistenceGuard.sanitize()` 当年就是「截断 + 塞标记」
     * 把用户的正文改成了第三种内容，还专挑最大的字段下手。
     */
    fun truncateProviderMessage(body: String, limit: Int = MAX_PROVIDER_MESSAGE_CHARS): String =
        if (body.length <= limit) body else body.substring(0, limit)
}

/** 一次网络失败的类别。不同类别的应对动作完全不同，所以不许合成一句「请求失败」。 */
enum class FailureClass {
    /** 上下文超限：重试没用，必须删内容或开新会话。 */
    ContextOverflow,

    /** 频率/额度：等一会儿再来才有意义（429）。 */
    RateLimited,

    /** 网关类失败（5xx 与 Cloudflare 全家）。 */
    Gateway,

    /** 网关等源站超时（408/504/524）：和一般 5xx 的区别是「多半已经花了时间」。 */
    GatewayTimeout,

    /** 用法错误（其余 4xx）：重发同一份内容只会再错一次。 */
    Client,

    /** 本地主动取消：不算失败，别弹红徽标。 */
    Cancelled,

    /** 一次都没收到响应（DNS/连接/TLS/读中断）。 */
    NoConnection,

    /** 看门狗判定静默超时：连接还在、但已经没有人在干活了。 */
    Stalled,

    /** 兜底，只在不该发生的地方出现。 */
    Unknown,
}
