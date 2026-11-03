package com.hualuo.engine.toolcalls

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装配器的纯 JVM 测试：钉的是「碎片怎么拼成一条完整调用」的全部形状约定——
 * 按 index 归组、name 只认第一份、arguments 追加、非流式形状用数组位置兜底、
 * 没名字的帧结账丢掉、空参数补 {}。这些拼错了，工具就会收到半截 JSON，账还记不到正主头上。
 */
class ToolCallAssemblerTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun fragmentsAreGluedByIndexAndArgumentsAppend() {
        val a = ToolCallAssembler()
        a.feed(obj("""{"index":0,"id":"call_1","function":{"name":"echo","arguments":"{\"q\":"}}"""))
        a.feed(obj("""{"index":0,"function":{"arguments":"1}"}}"""))

        val calls = a.finish()

        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("echo", calls[0].name)
        assertEquals("""{"q":1}""", calls[0].argumentsJson)
    }

    @Test
    fun parallelCallsStaySeparateAndComeBackInIndexOrder() {
        val a = ToolCallAssembler()
        // 先到的是 index 1（网关乱序发包真出现过），结账要按 index 收齐
        a.feed(obj("""{"index":1,"id":"call_b","function":{"name":"two","arguments":"{}"}}"""))
        a.feed(obj("""{"index":0,"id":"call_a","function":{"name":"one","arguments":"{}"}}"""))

        val calls = a.finish()

        assertEquals(2, calls.size)
        assertEquals("one", calls[0].name)
        assertEquals("two", calls[1].name)
    }

    @Test
    fun laterRepeatOfNameDoesNotOverwriteTheFirst() {
        val a = ToolCallAssembler()
        a.feed(obj("""{"index":0,"function":{"name":"echo"}}"""))
        a.feed(obj("""{"index":0,"function":{"name":"echo_evil_copy"}}"""))

        val calls = a.finish()

        assertEquals("echo", calls.single().name)
    }

    @Test
    fun nonStreamingShapeWithoutIndexUsesArrayPosition() {
        val a = ToolCallAssembler()
        a.feed(obj("""{"id":"call_x","function":{"name":"single","arguments":"{\"a\":1}"}}"""), defaultIndex = 0)

        val calls = a.finish()

        assertEquals("single", calls.single().name)
        assertEquals("""{"a":1}""", calls.single().argumentsJson)
    }

    @Test
    fun namelessFramesAreDroppedAtFinish() {
        val a = ToolCallAssembler()
        // 只有 index 没有 function 的心跳帧、有 arguments 却没名字的残帧：都不许拼出半条调用
        a.feed(obj("""{"index":0}"""))
        a.feed(obj("""{"index":1,"function":{"arguments":"{\"x\":1}"}}"""))

        assertEquals(2, a.count())
        assertTrue("没名字的整条丢掉：${a.finish()}", a.finish().isEmpty())
    }

    @Test
    fun emptyArgumentsBecomeEmptyObjectLiteral() {
        val a = ToolCallAssembler()
        a.feed(obj("""{"index":0,"id":"c","function":{"name":"noargs"}}"""))

        assertEquals("{}", a.finish().single().argumentsJson)
    }
}
