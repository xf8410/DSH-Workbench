package com.hualuo.engine.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * GitHub 扩展工具的**共用 JSON 小件**（P0 十四件：CI 操作 / commit diff / PR 读 /
 * Release / issue 全走这一份，防双源坑）。
 *
 * 规矩与 GitHubHttp.kt 一致：读不懂就返回 null 或空串，由调用方数「坏条目」出声；
 * 这里绝不抛异常，也绝不把「读不懂」冒充成「是空的」。
 */
internal val ghJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

/** 取字符串字段：没有 / 不是字符串 / 是 null -> 空串。 */
internal fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

/** 取长整型字段：读不出数字 -> null（不当 0 用，0 与「没有」在本仓是两件事）。 */
internal fun JsonObject.lng(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

/** 取整型字段。 */
internal fun JsonObject.num(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

/** 取布尔字段：只有字面 "true" 才算真。 */
internal fun JsonObject.flag(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.contentOrNull == "true"

/** 取嵌套对象。 */
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** 取嵌套数组。 */
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** 把一个 Kotlin 字符串编成 JSON 字符串字面量（含引号）。 */
internal fun ghQuote(s: String): String = JsonPrimitive(s).toString()

/** 把一段可能很长的原文压成一行并截断（错误体透传但这行不许撑爆上下文）。 */
internal fun ghFlat(raw: String, max: Int = 300): String {
    val flat = raw.replace('\n', ' ').replace('\r', ' ').trim()
    return if (flat.length <= max) flat else flat.take(max) + "…（已截断，原文 ${flat.length} 字符）"
}

/**
 * 统一的 HTTP 失败人话：只说状态码与出路，**绝不回显令牌**（令牌根本不在这层）。
 * 404 按「没有 / 没权限」两说，避免把「私有仓没给令牌」误报成「仓库不存在」。
 */
internal fun ghIssue(status: Int, body: String, what: String): String = when (status) {
    0 -> "连不上 GitHub：${ghFlat(body, 160)}"
    401 -> "令牌无效（401）：$what 没做成，重新设一次令牌再试"
    403 -> "GitHub 不给做（403）：$what 需要更大的令牌权限，或被限流"
    404 -> "GitHub 说没找到（404）：$what 的目标不存在，或令牌看不到它"
    409 -> "有冲突（409）：$what 时目标已经变了，重新读一遍再试"
    422 -> "GitHub 拒了参数（422）：${ghFlat(body)}$what 没做"
    451 -> "法律原因不可用（451）：$what 的目标被限制访问"
    else -> "GitHub 回了 $status：${ghFlat(body)}$what 没做"
}
