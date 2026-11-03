package com.hualuo.engine.toolcalls

/**
 * 工具清单里的一项（发进请求 tools 数组的形状）。
 *
 * [parametersJson] 是 JSON Schema 原文（对象），拼请求时原样嵌进去；
 * 读不懂的 schema 不让整条请求报废：拼装侧遇到坏 schema 会换成空对象 schema 兜底
 * （宁可按「无参数」报给对方，也不让一次手误把整个对话请求弄红）。
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,
)
