package com.hualuo.engine.language

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

/** 进制查看件测试：dump 行格式 / 嗅探 / 四进制换算 / 浮点分解 / 卷数账。 */
class HexDumpTest {

    @Test
    fun `dump 行三栏`() {
        val bytes = ByteArray(20) { it.toByte() }
        val rows = HexDump.dump(bytes)
        assertEquals(2, rows.size)
        assertEquals(0L, rows[0].offset)
        assertTrue(rows[0].hex.startsWith("00 01 02 03"))
        assertTrue(rows[0].ascii.startsWith("····"))
        assertEquals(16L, rows[1].offset)
    }

    @Test
    fun `可打印字符进 ASCII 栏`() {
        val bytes = "AB-".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 1)
        val rows = HexDump.dump(bytes)
        assertTrue(rows[0].ascii.startsWith("AB-"))
    }

    @Test
    fun `嗅探二进制与文本`() {
        assertTrue(HexDump.looksBinary(byteArrayOf(0, 1, 2, 3)))
        assertFalse(HexDump.looksBinary("hello 世界".toByteArray()))
        assertTrue(HexDump.decodableAsText("中文 UTF-8".toByteArray()))
        assertFalse(HexDump.decodableAsText(byteArrayOf(-1, -2, -3)))
    }

    @Test
    fun `四进制换算`() {
        val v = HexDump.radix("255")!!
        assertEquals("11111111", v.bin)
        assertEquals("377", v.oct)
        assertEquals("255", v.dec)
        assertEquals("ff", v.hex.takeLast(2))
    }

    @Test
    fun `前缀识别`() {
        assertEquals("ff", HexDump.radix("0xff")!!.hex.takeLast(2))
        assertEquals("255", HexDump.radix("0b11111111")!!.dec)
        assertEquals("255", HexDump.radix("0o377")!!.dec)
        assertEquals(0L, HexDump.radix("0x0")!!.value)
    }

    @Test
    fun `位分组账`() {
        val v = HexDump.view(-1L)
        assertTrue(v.bits.startsWith("11111111 11111111"))
        assertEquals("18446744073709551615", v.dec)
    }

    @Test
    fun `浮点分解`() {
        val f = HexDump.floatView(1.5)
        assertEquals(0, f.sign)
        assertTrue(f.hexBits.endsWith("f800000000000") || f.hexBits == "3ff8000000000000")
        assertEquals(1.5f, f.asFloat)
    }

    @Test
    fun `字节人话`() {
        assertEquals("512B", HexDump.humanBytes(512))
        assertEquals("1.0KB", HexDump.humanBytes(1024))
        assertEquals("1.5MB", HexDump.humanBytes(1536 * 1024))
    }

    @Test
    fun `坏输入给 null 不炸`() {
        assertEquals(null, HexDump.radix(""))
        assertEquals(null, HexDump.radix("nothex"))
        assertEquals(null, HexDump.radix("0x"))
    }

    @Test
    fun `分卷数边界`() {
        val chunk = 90L * 1024 * 1024
        assertEquals(0, HexDump.chunkCount(0L, chunk))
        assertEquals(1, HexDump.chunkCount(1L, chunk))
        assertEquals(1, HexDump.chunkCount(chunk, chunk))
        assertEquals(2, HexDump.chunkCount(chunk + 1, chunk))
        assertEquals(3, HexDump.chunkCount(3 * chunk - 1, chunk))
    }
}
