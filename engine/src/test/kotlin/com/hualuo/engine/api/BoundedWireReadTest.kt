package com.hualuo.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * [BoundedWireRead] 的账。重点钉两处搬的时候修的：
 * 末尾不带换行的一行必须交出去（原版当错误），错误文案不许再写"加密"。
 */
class BoundedWireReadTest {

    private fun bytes(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    @Test
    fun wholeBodyUnderLimitComesBackIntact() {
        assertEquals("你好 world", readBoundedText(bytes("你好 world"), 1024))
        assertEquals("", readBoundedText(bytes(""), 1024))
    }

    @Test
    fun wholeBodyOverLimitIsRefusedNotBuffered() {
        val payload = ByteArray(4096) { 'a'.code.toByte() }
        try {
            readBoundedText(ByteArrayInputStream(payload), 1000)
            fail("超过 1000 字节必须抛，不能默默读完")
        } catch (expected: WireLimitException) {
            assertTrue("消息要说清上限是多少：${expected.message}", expected.message!!.contains("1000"))
        }
    }

    @Test
    fun linesSplitOnNewlineAndCarriageReturnIsStripped() {
        val reader = BoundedLineReader(bytes("data: one\r\ndata: two\n"))
        assertEquals("data: one", reader.nextLine())
        assertEquals("data: two", reader.nextLine())
        assertNull("读完就该给 null", reader.nextLine())
        assertTrue("atEnd 要能告诉上层流是真结束了", reader.atEnd)
    }

    @Test
    fun lastLineWithoutNewlineStillCountsAsData() {
        // 这就是搬时修的 bug：原版用 readUtf8LineStrict，末尾没换行会抛 EOFException，
        // 然后一律报"Incomplete encrypted event line"，把服务端写完就关的最后一行丢掉。
        // 表现是"回答看着完整但客户端说流断了"。
        val reader = BoundedLineReader(bytes("data: a\ndata: tail-no-newline"))
        assertEquals("data: a", reader.nextLine())
        assertEquals("末尾这段是合法数据，必须交出去", "data: tail-no-newline", reader.nextLine())
        assertNull("交完下一次才是 null", reader.nextLine())
    }

    @Test
    fun oversizedSingleLineThrowsWithReadableMessage() {
        val reader = BoundedLineReader(bytes("x".repeat(100)), maxLineBytes = 10)
        try {
            reader.nextLine()
            fail("巨行必须被拦")
        } catch (expected: WireLimitException) {
            val message = expected.message!!
            assertTrue("消息要带上限：$message", message.contains("10"))
            // 修掉的第二处：这句以前写 "encrypted event line"，会把排查的人往错方向带
            assertTrue("文案不许提加密：$message", !message.contains("加密") && !message.contains("encrypted"))
        }
    }

    @Test
    fun blankLineIsStillALine() {
        // SSE 用空行分事件，空行不能被当成"没有内容"
        val reader = BoundedLineReader(bytes("\n"))
        assertEquals("", reader.nextLine())
        assertNull(reader.nextLine())
    }

    @Test
    fun badLimitIsRejectedAtOnce() {
        val readers = listOf(BoundedLineReader(bytes("x"), 0L), BoundedLineReader(bytes("x"), Long.MAX_VALUE))
        for (reader in readers) {
            try {
                reader.nextLine()
                fail("封顶写错要当场报，不许悄悄放行")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("正数"))
            }
        }
    }
}
