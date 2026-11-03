package com.hualuo.engine.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把提供商返回的错误体翻成 [GenerationError.Api]。**搬自原版 Agora 的 `api/ProviderHttpError.kt`**，
 * 逻辑照用（那套"形状五花八门都要认"的取值顺序是对的），加了两件事（CI 首跑抓到后补的）：
 *
 *  1. **所有从对方 body 里取出来的文本原文直通**（零脱敏纪律）。
 *     为什么加：错误体是**不可信输入**，而有些网关会把请求上下文原样回显在 message 里 ——
 *     里面可能带着 `api_key=...` 或整段 `Authorization: Bearer sk-...`。原版直接把这段文本送进
 *     界面与日志，等于把密钥抄了一遍。分类逻辑没错，出口没设防，这是搬代码时最容易漏的那一格。
 *  2. **状态码永远进 code、读不出形状必须出声**。第一版在「没有 body」和「body 不是可读形状」
 *     两条路上把 statusCode 丢了（code=null），userMessage 的分类就哑了；无结构 body 又把原始
 *     JSON 直接当 message 递给人看，既不说是怎么回事也不带状态码出路。
 *
 * 认的形状（原版经验，全保留）：
 * - `{"error":{"message":...,"code":...,"type":...}}` —— OpenAI 标准
 * - `{"error":"纯字符串"}` —— 不少自建网关
 * - `{"message":...}` / `{"detail":...}` / `{"reason":...}` / `{"error_description":...}` —— FastAPI/OAuth/各家
 * - `type` 取不到时退到 `status`（gRPC 风网关把状态放这儿）
 * - 顶层 `{"error":{...}}` 里找不到 message 时，再回退查顶层
 * - 根本不是 JSON（HTML 错误页、纯文本）—— 原样当 message，但标记 structured=false
 */
data class ProviderHttpErrorBody(
    val code: String?,
    val type: String?,
    val message: String,
    /** true = 从 JSON 里按字段取出来的；false = 整坨原文（可能是 HTML）。 */
    val structured: Boolean,
)

private val lenientJson = Json { ignoreUnknownKeys = true }

/** 解析不出 JSON 时返回 null，让调用方决定"要不要显示原文"。 */
fun parseProviderHttpErrorBody(rawBody: String): ProviderHttpErrorBody? {
    val trimmed = rawBody.trim()
    if (trimmed.isEmpty()) return null

    val root = runCatching { lenientJson.parseToJsonElement(trimmed) }.getOrNull()
        ?: return unstructured(trimmed)

    return when (root) {
        is JsonObject -> parseErrorObject(root) ?: unstructured(trimmed)
        // 这里踩过一个库怪癖（CI 红出来才认的）：kotlinx 的 parseToJsonElement 对
        // **不含空白的顶层裸文本**（如 `<html>oops</html>`）能解析成字符串 primitive，
        // 带空白的（`<html><body>502 ...`）才抛。primitive 不等于 JSON 字符串 ——
        // 只有原文真用引号包起来（"额度用完了" 这种）才算结构化，否则一律按整坨原文处理。
        is JsonPrimitive -> nonBlankPrimitive(root)
            ?.takeIf { trimmed.startsWith("\"") }
            ?.let { ProviderHttpErrorBody(null, null, mask(it), true) }
            ?: unstructured(trimmed)
        else -> unstructured(trimmed)
    }
}

/** HTTP 非 2xx 时统一入口：没有 body 也要给出一条能看的（带状态码）。 */
fun providerHttpError(statusCode: Int, rawBody: String?): GenerationError.Api {
    // 状态码是这里唯一确定的事实，三条路都必须把它填进 code：
    // userMessage 靠数字 code 走「同一个码同一条出路」的分类，丢了它就只能给平话。
    if (rawBody == null) {
        return GenerationError.Api(statusCode.toString(), null, "HTTP $statusCode（对方没给原因）")
    }
    val parsed = parseProviderHttpErrorBody(rawBody)
        ?: return GenerationError.Api(statusCode.toString(), null, "HTTP $statusCode（对方给的不是能读的形状）")
    val code = parsed.code ?: statusCode.toString()
    // 无结构 body（HTML 错误页、整坨读不懂的 JSON）不许把原文悄悄当 message 递出去：
    // 先出声说明「不是能读的形状」，原文作为线索附在后面。
    val message = if (parsed.structured) {
        parsed.message
    } else {
        "HTTP $statusCode（对方给的不是能读的形状）：" + parsed.message
    }
    return GenerationError.Api(code, parsed.type, message)
}

/** 只有确认是 JSON 里取出来的 message 才用这个；HTML 之类的原文要走 providerHttpError。 */
fun structuredErrorMessage(rawBody: String): String? =
    parseProviderHttpErrorBody(rawBody)?.takeIf { it.structured }?.message

private fun parseErrorObject(root: JsonObject): ProviderHttpErrorBody? {
    val nested = root["error"] as? JsonObject
    val message = nested?.firstErrorMessage()
        ?: nonBlankPrimitive(root["error"])
        ?: root.firstErrorMessage()
        ?: return null
    val code = nested?.string("code") ?: root.string("code")
    val type = nested?.string("type") ?: nested?.string("status") ?: root.string("type") ?: root.string("status")
    return ProviderHttpErrorBody(code, type, mask(message), structured = true)
}

private fun JsonObject.firstErrorMessage(): String? =
    string("message") ?: string("detail") ?: string("reason") ?: string("error_description")

private fun JsonObject.string(key: String): String? = nonBlankPrimitive(this[key])

private fun nonBlankPrimitive(element: JsonElement?): String? =
    (element as? JsonPrimitive)
        ?.takeUnless { it === JsonNull }
        ?.let { runCatching { it.content }.getOrNull() }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun unstructured(raw: String) = ProviderHttpErrorBody(null, null, mask(raw), structured = false)

// 零脱敏纪律：原文直通，一字不改（密钥/令牌/URL 全按实际内容显示）
private fun mask(text: String): String = text
