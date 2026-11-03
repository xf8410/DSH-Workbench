package com.hualuo.engine.apk

import org.junit.Test
import org.junit.Assert.assertEquals
import java.io.ByteArrayInputStream

/** AXML 解析测试：手拼最小 manifest 字节（串池 UTF-8 + StartTag 三属性），全离线。 */
class AxmlManifestTest {

    /** 拼最小 AXML：StringPool(UTF-8) + manifest StartTag（package/versionCode/versionName）。 */
    private fun buildAxml(): ByteArray {
        val strings = listOf(
            "manifest", "package", "versionCode", "versionName",
            "http://schemas.android.com/apk/res/android", "com.example.test", "1.2.3",
        )
        // 串池数据区
        val dataBuf = java.io.ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        for (s in strings) {
            require(s.length < 128) { "测试串用短串（变长前缀 <0x80 各 1 字节）" }
            offsets += dataBuf.size()
            dataBuf.write(s.length)     // u16len（变长，短串 1 字节）
            dataBuf.write(s.length)     // u8len（变长，短串 1 字节）
            dataBuf.write(s.toByteArray(Charsets.UTF_8))
            dataBuf.write(0)
        }
        val data = dataBuf.toByteArray()
        val headerSize = 28
        val offsetsSize = strings.size * 4
        val poolSize = headerSize + offsetsSize + data.size
        val pool = java.io.ByteArrayOutputStream()
        val le16 = { v: Int -> byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()) }
        val le32 = { v: Int ->
            byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
        }
        pool.write(le16(0x0001)); pool.write(le16(headerSize)); pool.write(le32(poolSize))
        pool.write(le32(strings.size)); pool.write(le32(0)); pool.write(le32(0x100))
        pool.write(le32(headerSize + offsetsSize)); pool.write(le32(0))
        offsets.forEach { pool.write(le32(it)) }
        pool.write(data)

        // manifest StartTag：attr = ns,name,rawValue + typedValue{size=8,res0=0,type,data}
        val manifestIdx = 0
        val androidNsIdx = 4
        fun attr(nameIdx: Int, rawIdx: Int, type: Int, data: Int, hasAndroidNs: Boolean = true): ByteArray {
            val o = java.io.ByteArrayOutputStream()
            o.write(le32(if (hasAndroidNs) androidNsIdx else -1))
            o.write(le32(nameIdx))
            o.write(le32(rawIdx))
            o.write(le16(8))            // typed value size（2 字节，attr 总长 20）
            o.write(0)                  // res0
            o.write(type)               // type（1 字节）
            o.write(le32(data))
            return o.toByteArray()
        }
        val attrs = listOf(
            attr(1, 5, 0x03, 5, hasAndroidNs = false), // package raw="com.example.test"（无 ns）
            attr(2, -1, 0x10, 26),      // android:versionCode INT_DEC=26
            attr(3, -1, 0x03, 6),       // android:versionName "1.2.3"
        )
        val attrExtSize = 20
        val chunkSize = 16 + attrExtSize + attrs.sumOf { it.size }
        val tag = java.io.ByteArrayOutputStream()
        tag.write(le16(0x0102)); tag.write(le16(16)); tag.write(le32(chunkSize))
        tag.write(le32(0)); tag.write(le32(-1))                    // line, comment
        tag.write(le32(-1)); tag.write(le32(manifestIdx))          // ns, name
        tag.write(le16(20)); tag.write(le16(20)); tag.write(le16(attrs.size)) // attrStart/Size/Count
        tag.write(le16(0)); tag.write(le16(0))                     // id/style index
        tag.write(le16(0))                                          // 对齐到 attrStart=20（attrExt 18B + 2B）
        attrs.forEach { tag.write(it) }

        val xmlHeader = java.io.ByteArrayOutputStream()
        xmlHeader.write(le16(0x0003)); xmlHeader.write(le16(8)); xmlHeader.write(le32(8 + pool.size() + tag.size()))
        return xmlHeader.toByteArray() + pool.toByteArray() + tag.toByteArray()
    }

    @Test
    fun `手拼 AXML 抽出包名与版本`() {
        val info = AxmlManifest.parse(ByteArrayInputStream(buildAxml()))
        assertEquals("com.example.test", info.packageName)
        assertEquals("1.2.3", info.versionName)
        assertEquals(26L, info.versionCode)
        assertEquals(7, info.stringCount)
    }
}
