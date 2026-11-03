package com.hualuo.engine.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧编解码的纯 JVM 契约：字节长度 vs 字符长度的中文老坑、零头不丢、多帧连发、
 * 坏帧判死（无 Content-Length / 负数 / 超上限）。
 */
class LspFramingTest {

    @Test
    fun contentLengthCountsBytesNotCharacters() {
        // 中文 6 个字符 = 18 字节；按字符算会截断
        val body = "{\"m\":\"中文中文中文\"}"
        val frame = LspFraming.encode(body)
        val header = String(frame, 0, frame.size - body.toByteArray(Charsets.UTF_8).size)
        assertTrue(
            "长度必须是 18 字节不是 12 字符：$header",
            header.contains("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}"),
        )

        val decoded = LspFraming.decode(frame)
        assertEquals(listOf(body), decoded.messages)
        assertNull(decoded.fatal)
    }

    @Test
    fun partialFrameRemainderIsKept() {
        val frame = LspFraming.encode("{\"a\":1}")
        val cut = frame.copyOfRange(0, frame.size - 3)

        val first = LspFraming.decode(cut)
        assertTrue(first.messages.isEmpty())
        assertEquals("零头必须原样留：${first.remainder.size}", cut.size, first.remainder.size)

        val second = LspFraming.decode(first.remainder + frame.copyOfRange(frame.size - 3, frame.size))
        assertEquals(listOf("{\"a\":1}"), second.messages)
    }

    @Test
    fun twoFramesInOneBufferBothDecode() {
        val buffer = LspFraming.encode("{\"n\":1}") + LspFraming.encode("{\"n\":2}")
        val decoded = LspFraming.decode(buffer)

        assertEquals(listOf("{\"n\":1}", "{\"n\":2}"), decoded.messages)
        assertEquals(0, decoded.remainder.size)
    }

    @Test
    fun missingContentLengthIsFatal() {
        val bad = "X-Whatever: 1\r\n\r\n{}".toByteArray(Charsets.UTF_8)
        val decoded = LspFraming.decode(bad)

        assertTrue(decoded.messages.isEmpty())
        assertNotNull("没有 Content-Length = 对方不按 LSP 说话：判死", decoded.fatal)
    }

    @Test
    fun oversizeDeclaredLengthIsFatalWithoutAllocation() {
        val bad = "Content-Length: ${LspFraming.MAX_BODY_BYTES + 1}\r\n\r\n".toByteArray(Charsets.UTF_8)
        val decoded = LspFraming.decode(bad)

        assertTrue(decoded.messages.isEmpty())
        assertNotNull("离谱长度当场判死，不分配", decoded.fatal)
        assertTrue(decoded.fatal!!.contains("上限"))
    }

    @Test
    fun messageFactoryShapesAreValid() {
        val req = LspMessages.request(1, "initialize", """{"processId":null}""")
        assertTrue(req.contains("\"id\":1"))
        assertTrue(req.contains("\"method\":\"initialize\""))

        val note = LspMessages.notification("initialized", "{}")
        assertTrue("通知不带 id", !note.contains("\"id\""))

        val err = LspMessages.errorResponse(7, -32601, "method not found")
        assertTrue(err.contains("\"code\":-32601"))
        assertTrue(err.contains("method not found"))
    }
}
