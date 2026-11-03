package com.hualuo.engine.api

import com.hualuo.engine.http.FailureClass

/**
 * 生成失败的分类。**搬自原版 Agora 的 `api/GenerationError.kt`**（那套分类是对的，照用），
 * 但 `userMessage()` 整段重写：原版有四处会让人查不下去或者看到错东西（见每处注释）。
 *
 * 分类的价值在于**不同错给不同出路**：401 是去改密钥、429 是等一会儿、404 是 base 或模型名写错、
 * 断流是"这段回答不完整别当成品"。全塞成一句"请求失败"就等于没分类。
 * 界面与决策层（RetryPolicy 的 reason）里给用户看的句子**一律中文**——错误码可以原样带上
 * （502、context_length_exceeded 这些是排查线索），但「下一步动哪里」必须是中文说清。
 *
 * 原版四问题（都在这版修掉）：
 *  1. 提示语全是英文，而这个 App 的界面是中文 —— 直接搬过来你会看到一句英文报错。
 *  2. `SseParse` 把 `rawLine` 与 `cause` 全丢了，只剩一句 "Failed to parse server response."：
 *     对方返的不合规矩时**一点线索都没有**，只能干瞪眼。现在带原文片段（超长带账截断）。
 *  3. `Unknown` 直接 `cause.localizedMessage` 进界面：异常文本里可能带**完整 URL**
 *     （有些网关把密钥写在查询串上）——零脱敏纪律：原文照显示，一字不改。
 *  4. 403 与 404 都掉进 "Network error (xxx)"：404 十有八九是 base URL 或模型名写错，
 *     403 常是额度/未开通，这两条必须指名道姓，否则人只会去重启 App。
 */
sealed class GenerationError {

    /** HTTP 层：连上了但状态码不对。 */
    data class Network(val statusCode: Int, val message: String) : GenerationError()

    /** 对方按协议回了错误体（无效密钥、限流、服务端错）。 */
    data class Api(val code: String?, val type: String?, val message: String) : GenerationError()

    /**
     * 传输层失败：连接级别的事实（压根没连上、静默卡死、用户取消、超限没状态码可用）。
     * 接线层（ChatWireRunner）把 RetryPolicy 的 GiveUp 翻译成这一类，
     * 出路按类别给 —— 别拿 "HTTP 0" 这种没有状态码的假 Network 糊弄人。
     */
    data class Transport(val failure: FailureClass, val detail: String) : GenerationError()

    /** SSE 某一行读不懂。[rawLine] 会在给人看的那句里带出来（超长带账截断）。 */
    data class SseParse(val rawLine: String, val cause: String) : GenerationError()

    /**
     * 流结束了但**没有语义上的收尾标记**（`[DONE]` / `finish_reason` / `message_stop`）：
     * 这回答是可证明的不完整，不是"说完了"。常见形状是中继在内容块边界掐断，
     * 正好卡在要开始写工具调用的地方 —— 只看 HTTP 200 是发现不了的。
     */
    data class IncompleteStream(
        val provider: String,
        val stopReason: String?,
        val toolCallInFlight: Boolean,
        val producedContent: Boolean,
    ) : GenerationError()

    /** 撞到输出 token 上限被截断（思考模型上，思考与回答共用这个额度）。 */
    data class OutputTruncated(val provider: String, val stopReason: String?) : GenerationError()

    /** 工具执行失败（记忆、联网、shell、RAG）。 */
    data class ToolExecution(val toolName: String, val arguments: String, val message: String) : GenerationError()

    /**
     * 工具回合打到轮次上限（模型连轴转地调工具，迟迟不给答案）：防无限打转的闸门。
     * 这不是网络错也不是模型错，是「这题该拆小」——出路必须这么指。
     */
    data class ToolLoopLimit(val rounds: Int) : GenerationError()

    /** 图片/视频/PDF 转写失败。 */
    data class Transcription(val path: String, val kind: String, val message: String) : GenerationError()

    /** 向量计算失败。 */
    data class Embedding(val modelId: String, val message: String) : GenerationError()

    /** 配置缺东西或不合法（没密钥、没地址、模型没选）。 */
    data class Configuration(val message: String) : GenerationError()

    /** 请求在本地就被拒（发出去之前发现形状不对）。 */
    data class RequestFormat(val provider: String, val details: String) : GenerationError()

    /** 没预料到的异常。 */
    data class Unknown(val cause: Throwable) : GenerationError()

    /** 用户自己按了停止。 */
    object Cancelled : GenerationError()

    /** 等回应超时。 */
    object Timeout : GenerationError()

    /** 给你看的那一句。中文，且**指得出下一步动哪里**。 */
    fun userMessage(): String = when (this) {
        is Network -> when (statusCode) {
            400 -> "请求被对方拒了（400）：${brief(message)}。多半是模型名或参数不合它家的规矩"
            401 -> "鉴权失败（401）：提供商里的密钥不对或已失效，去「提供商」里重填这一家。对方原话：${brief(message)}"
            403 -> "对方不许访问（403）：额度用尽、模型未开通，或这个密钥没权限：${brief(message)}"
            404 -> "找不到这个地址或模型（404）：先核对「提供商」里的 base URL 末尾版本段与模型名：${brief(message)}"
            408, 429 -> "对方忙或限流（$statusCode）：等一会儿再发，或把并发降下来"
            413 -> "请求太大（413）：这条内容超了对方上限，删掉部分附件或缩短上下文再发"
            // 5xx 也必须带原文片段：临时挂与配额尽看起来一样，线索都在对方那句话里。
            in 500..599 -> "对方服务出错（$statusCode）：${brief(message)}。可能是它临时挂了，稍后重试；连着几次都这样就去查这家提供商"
            else -> "网络出错（$statusCode）：${brief(message)}"
        }
        is Api -> {
            val status = code?.trim()?.toIntOrNull()
            if (status != null && status in 100..599) {
                // 数字 code 就是 HTTP 状态码（providerHttpError 兜底填的正是它）：
                // 同一个码走同一条出路，不许落到「code：message」的平话路径上。
                Network(status, message).userMessage()
            } else {
                val head = buildString {
                    if (!code.isNullOrBlank()) append(code)
                    if (!type.isNullOrBlank()) {
                        if (isNotEmpty()) append(" ")
                        append(type)
                    }
                }
                if (head.isEmpty()) brief(message) else "$head：${brief(message)}"
            }
        }
        is Transport -> when (failure) {
            FailureClass.Cancelled -> "你按了停止，这次不算失败，也不会替你重发"
            FailureClass.NoConnection -> "没连上对方：${brief(detail)}。检查网络，再核对「提供商」里的 base URL 写得对不对"
            FailureClass.Stalled -> "连接卡住了：${brief(detail)}。要是内容已经出过一部分，别整条盲重发（会重复内容、再花一遍钱）"
            // 网关 502/400 包着 "Your input exceeds the context window" 时走的是这里：
            // 出路固定是「删历史/开新会话」，绝不许说成"稍后重试"——重发同一份只会再错一次。
            // detail 是对方错误体原文，留它当证据，人对得上是哪段内容撑爆的。
            FailureClass.ContextOverflow ->
                if (detail.isBlank()) "上下文超限：删掉部分历史或开新会话再发；重发同一份内容只会再错一次"
                else "上下文超限：删掉部分历史或开新会话再发，别重发同一份。对方原话：「${brief(detail)}」"
            else -> "网络出错（$failure）：${brief(detail)}"
        }
        // 修：不再只给一句"解析失败"，带上原文片段（超长带账截断）
        is SseParse -> "对方返回的内容读不懂（$cause）。看到的开头：「${brief(rawLine)}」——" +
            "通常是中间有代理改写了响应，或这家不完全是 OpenAI 兼容协议"
        is IncompleteStream -> buildString {
            append("$provider 的回答在半路断了")
            if (toolCallInFlight) append("，当时正在写一个工具调用")
            if (producedContent) append("（已经吐出来的那部分不完整，别当成品用）")
            append("。")
            append(
                if (stopReason == null) "没收到收尾标记，所以这段是可证明的没说完；可以重发一次"
                else "收尾标记之前流就关了（stop_reason=$stopReason）",
            )
        }
        is OutputTruncated ->
            "回答撞到输出长度上限被切掉（stop_reason=${stopReason ?: "max_tokens"}）。" +
                "把设置里的最大输出 token 调大，或把思考预算调小，再重发"
        is ToolExecution -> "工具「$toolName」执行失败：${brief(message)}（参数：${brief(arguments)}）"
        // 打转闸的出路不是「重试」而是「拆小」：重发同一份问题只会再打一次转。
        is ToolLoopLimit -> "工具来回 $rounds 轮还没给出答案：先停下（防无限打转）。" +
            "把问题拆小一点，或者直接点名要哪一步的结果（比如「先列出我的仓库」）"
        is Transcription -> "${kind}转写失败：${brief(message)}（$path）"
        is Embedding -> "向量计算失败（$modelId）：${brief(message)}"
        is Configuration -> "设置还没配好：$message"
        is RequestFormat -> "请求在发出去之前就被拦下（$provider）：$details"
        is Unknown -> "没预料到的错：${brief(cause.localizedMessage ?: cause.toString())}"
        Cancelled -> "你已经按了停止"
        Timeout -> "等回应超时了：对方可能挂了或网络不通，稍后重试"
    }

    companion object {
        /** 给人看的片段封顶：对方可能回一整页 HTML 错误。 */
        private const val EXCERPT_LIMIT = 220

        /**
         * 折行，按**原文**长度截断（超长必带"已截断"标记）。
         *
         * 零脱敏纪律（ULTIMATE_RULE ZERO）：错误原文一字不改地透传——
         * 密钥、令牌、URL 全按实际内容显示。截断只为一整页 HTML 不刷屏，
         * 且带账明说截了多少，不是隐藏。
         */
        private fun brief(text: String): String {
            val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
            return if (flat.length <= EXCERPT_LIMIT) flat
            else flat.take(EXCERPT_LIMIT) + "…（已截断，原文 ${flat.length} 字符）"
        }
    }
}

