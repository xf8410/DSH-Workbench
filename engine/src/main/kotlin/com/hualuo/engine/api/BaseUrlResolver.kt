package com.hualuo.engine.api

/**
 * 自定义端点 base URL 的补齐与回退。**搬自原版 Agora 的 `api/BaseUrlResolver.kt`**，
 * 搬的时候修了一处真 bug（见下）。
 *
 * 规矩跟原版一致：URL 里已经带版本号（`/v1`、`/v1beta`、`/compatible-mode/v1`、`/v2`）就**不再补** `/v1`；
 * 没带才补。`withoutTrailingVersion` 只剥**结尾**那一段版本，像 `/v1/proxy` 这种中间带版本的不动它。
 *
 * ## 搬之前先理解的 bug（原版在这里会算错）
 * 原版判断"有没有版本段"用的是在整个 URL 上找 `/v数字`：
 * `Regex("/v\\d").containsMatchIn(url)`。这会把**主机名**也算进去 ——
 * 比如 `https://v1.internal.example.com/chat`，`://` 后面紧跟 `v1`，正则里那个 `/` 命中的是
 * 协议分隔符的第二个斜杠，于是这个 URL 被当成"已经带版本号"，`withV1` 就不补了，
 * 请求打到 `https://v1.internal.example.com/chat`（少一段 `/v1`），报 404 还查不出为什么。
 * 内网网关、自建反代、`v2.xxx.com` 这类域名真长这样，不是假设。
 * 修法：**只在路径部分找**，而且要求是**完整的一段**（前面是 `/` 或开头，后面是 `/` 或结尾）。
 * 顺带把"段里只能是 v + 数字 + 少量后缀"钉死，避免把 `/video`、`/vlog2` 当成版本段。
 */
object BaseUrlResolver {

    /**
     * 路径里的一段版本号：开头 `/v`，紧跟至少一位数字，后面可跟 `[A-Za-z0-9._-]`（`v1beta` 这种），
     * 且必须到段尾或下一个 `/` 为止。
     */
    private val VERSION_SEGMENT_IN_PATH = Regex("/v\\d+[A-Za-z0-9._-]*(?=/|$)")

    /** 只认结尾那一段版本，剥它的时候要求整段匹配，不吞 `/v1/proxy` 的中间段。 */
    private val TRAILING_VERSION_SEGMENT = Regex("/v\\d+[A-Za-z0-9._-]*$", RegexOption.IGNORE_CASE)

    /**
     * 切出 URL 的**路径**部分（含开头的 `/`），主机名与查询串都不算。
     *
     * 不用 java.net.URI：用户手填的 base 经常是 `api.x.com/v1` 这种缺协议的写法，
     * URI 会把它整个当 scheme 或者抛异常，反而不如自己按第一个 `/`（跳过 `://`）切来得稳。
     */
    private fun pathPart(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        val schemeEnd = trimmed.indexOf("://")
        val from = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val queryCut = trimmed.indexOfAny(charArrayOf('?', '#'), from).let { if (it < 0) trimmed.length else it }
        val slash = trimmed.indexOf('/', from)
        return if (slash < 0 || slash >= queryCut) "" else trimmed.substring(slash, queryCut)
    }

    /** 路径里是不是已经有版本段。只看路径，主机名里的 `v1` 不算（修掉的 bug 就在这）。 */
    fun hasVersionSegment(url: String): Boolean =
        VERSION_SEGMENT_IN_PATH.containsMatchIn(pathPart(url).trimEnd('/'))

    /** 没带版本就补 `/v1`；空串原样给空串，不硬造一个 `/v1` 出来。 */
    fun withV1(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (hasVersionSegment(trimmed)) trimmed else "$trimmed/v1"
    }

    /**
     * 剥掉结尾的版本段，给不出（本来就没有或剥完就空了）就返回 null。
     * 只剥结尾：`/v1/proxy` 保持不动，那是老同步逻辑不会碰的写法。
     */
    fun withoutTrailingVersion(url: String): String? {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val path = pathPart(trimmed)
        if (path.isEmpty()) return null
        val match = TRAILING_VERSION_SEGMENT.find(path) ?: return null
        val keptPath = path.removeRange(match.range).trimEnd('/')
        val authorityEnd = trimmed.length - path.length
        val rebuilt = (trimmed.substring(0, authorityEnd) + keptPath).trimEnd('/')
        return rebuilt.takeIf { it.isNotBlank() && !it.endsWith("://") }
    }

    /**
     * 拼一个端点：base 已带版本就只接路径，不重复版本段；`/` 多了也只用一个。
     *
     * 清账说明：搬来的第一版这里挂过一个 `lowercase().let { 原值 }` 的空转表达式
     * （算了小写又扔掉，等于没算），当时没人调用它、CI 也看不出死码；现在
     * OpenAiCompatClient 成了真用户，把它简化回直白的拼接 —— 行为逐字不变
     * （端点小写与否由调用方自己负责，这里从不改大小写，那表达式本来就没改成）。
     */
    fun endpoint(url: String, suffix: String): String {
        val base = url.trim().trimEnd('/')
        val tail = suffix.trim().trimStart('/')
        if (base.isEmpty()) return tail
        if (tail.isEmpty()) return base
        return "$base/$tail"
    }
}
