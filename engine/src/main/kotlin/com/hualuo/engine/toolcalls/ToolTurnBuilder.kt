package com.hualuo.engine.toolcalls

import com.hualuo.engine.api.ChatTurn
import com.hualuo.engine.api.WireToolCall

/**
 * 工具结果喂回上下文时的形状裁剪（纯函数，纯 JVM 好测）。
 *
 * 为什么必须有它：工具结果（读文件、看日志）动辄上万字，原样塞回历史，
 * 几次工具往返就能把上下文顶爆——「工具结果有界进上下文」是这条路线的红线。
 * 裁法是保守的：留头部、写清楚「省了多少字、要完整就再要」，绝不悄悄丢中间。
 */
object ToolResultTrimmer {

    /** 喂回上限：每条工具结果最多这么多字符。 */
    const val RESULT_LIMIT = 4000

    /**
     * 裁一条工具结果：[text] 超 [RESULT_LIMIT] 就留头 + 带账，
     * 否则原样返回。返回的永远是「读得懂来龙去脉」的文本，不是硬切。
     */
    fun trim(text: String): String {
        if (text.length <= RESULT_LIMIT) return text
        val head = text.take(RESULT_LIMIT)
        val cut = text.length - RESULT_LIMIT
        return buildString {
            append(head)
            append("\n…（工具结果过长，这里省了 $cut 字；要完整内容就分段再要，或指明看哪一段）")
        }
    }
}

/**
 * 生成循环里「工具这一段」的形状约定（给 OpenAiCompatClient 拼请求用）：
 * 一轮工具回合往历史里回填两条——assistant（带 tool_calls）与 tool（带结果）。
 *
 * 做成函数而不是散着拼：tool_call_id 错一处，模型就「接不上话」，然后原地重复调同一个工具
 * （本地网关的经典死循环）。这里一把拼对。
 */
object ToolTurnBuilder {

    /** assistant 回填条：模型这轮说过的文本（可能为空）+ 要调的清单。 */
    fun assistantTurn(previousText: String, calls: List<AssembledToolCall>): ChatTurn = ChatTurn(
        role = "assistant",
        content = previousText,
        toolCalls = calls.map { WireToolCall(id = it.id, name = it.name, argumentsJson = it.argumentsJson) },
    )

    /** tool 结果条：id 对上 + 裁剪后的结果文本。 */
    fun toolResultTurn(call: AssembledToolCall, resultText: String): ChatTurn = ChatTurn(
        role = "tool",
        content = ToolResultTrimmer.trim(resultText),
        toolCallId = call.id,
    )
}
