package com.hualuo.engine.toolcalls

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一个**装配完整**的工具调用（流式碎片拼完之后的样子）。
 *
 * 流上每帧只给一小块：第一块带 id 与函数名，arguments 是一小段一小段拼过来的
 * （有的网关还会在中间插不同 index 的调用，真见过并行发两个）。装配的活儿归
 * [ToolCallAssembler] 一个人干，别在各处再拼一遍——拼错一次，工具就会收到
 * 半截 JSON，然后「参数解析失败」的锅还甩不到正主头上。
 */
data class AssembledToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/**
 * 流式工具调用碎片的装配器（纯 JVM，逐帧喂）。
 *
 * 形状按 OpenAI 兼容协议：choice.delta.tool_calls 是数组，每项
 * `{"index":0,"id":"call_x","function":{"name":"...","arguments":"{\"a\":"}}`，
 * 后续帧只有 `{"index":0,"function":{"arguments":"1}"}}`——**按 index 归组、按到达顺序拼接**。
 * 非流式的 message 形状里条目没有 index：调用方把数组位置当默认 index 传进来（defaultIndex）。
 *
 * 纪律：
 *  - name 只认第一份非空的（后面的重复帧不覆盖，覆盖会把名字拼坏）；
 *  - arguments 一律追加（不清空——清空会吞掉开头几帧的参数）；
 *  - 别的字段（id 的重复帧等）忽略，坏帧跳过不炸。
 */
class ToolCallAssembler {

    private class Part {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    private val parts = LinkedHashMap<Int, Part>()

    /** 喂一帧 tool_calls 里的一个条目。缺 index 的用 [defaultIndex]（非流式形状的数组位置）。 */
    fun feed(item: JsonObject, defaultIndex: Int = 0) {
        val index = (item["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: defaultIndex
        val part = parts.getOrPut(index) { Part() }
        (item["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            if (part.id.isEmpty()) part.id = it
        }
        val fn = item["function"] as? JsonObject ?: return
        (fn["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            if (part.name.isEmpty()) part.name = it
        }
        (fn["arguments"] as? JsonPrimitive)?.contentOrNull?.let { part.args.append(it) }
    }

    /** 装起来的条目数（含没名字的垃圾帧；收尾判「当时是不是在写工具调用」用它）。 */
    fun count(): Int = parts.size

    /**
     * 结账：按 index 升序给出完整调用。名字为空的（纯粹是垃圾帧）整条丢掉，
     * arguments 为空串的补上 `{}`（合法空参数，让执行侧照常走到「缺参数」的出声）。
     */
    fun finish(): List<AssembledToolCall> = parts.entries
        .sortedBy { it.key }
        .mapNotNull { (_, p) ->
            if (p.name.isEmpty()) return@mapNotNull null
            AssembledToolCall(
                id = p.id,
                name = p.name,
                argumentsJson = p.args.toString().ifBlank { "{}" },
            )
        }
}
