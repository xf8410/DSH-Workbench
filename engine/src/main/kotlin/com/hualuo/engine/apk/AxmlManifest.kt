package com.hualuo.engine.apk

import java.io.InputStream

/**
 * AXML（Android 二进制 XML）manifest 解析（560 清单 151-153）：从 APK 的
 * AndroidManifest.xml（二进制）抽 包名 / versionName / versionCode / compileSdk。
 *
 * 做法：顺序扫 chunk（先 StringPool 后 StartTag），只认账不改写；乱格式如实报错
 * 不猜（解析器版本进了 Artifact 语义，解析结果必须可信）。
 * 纯 JVM 可测（测试里手拼 AXML 字节，不依赖 Android 运行时）。
 */
object AxmlManifest {

    data class ManifestInfo(
        val packageName: String?,
        val versionName: String?,
        val versionCode: Long?,
        val compileSdkVersion: String?,
        val compileSdkVersionCodename: String?,
        /** 解析账：读了多少 chunk、串池多大——出问题时能对账。 */
        val chunkCount: Int,
        val stringCount: Int,
    )

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** 从 manifest 条目流解析。流读完即关。 */
    fun parse(input: InputStream): ManifestInfo {
        val bytes = input.use { it.readBytes() } // manifest 一般几十 KB，条目级读取不破红线二
        var strings: List<String> = emptyList()
        var chunkCount = 0
        var off = 0
        var pkg: String? = null
        var vName: String? = null
        var vCode: Long? = null
        var compileSdk: String? = null
        var compileSdkCode: String? = null
        while (off + 8 <= bytes.size) {
            val type = le16(bytes, off)
            val headerSize = le16(bytes, off + 2)
            val size = le32(bytes, off + 4)
            if (size < 8 || off + size > bytes.size) break
            chunkCount++
            when (type) {
                RES_STRING_POOL_TYPE -> strings = parseStringPool(bytes, off)
                // 文件头：size 字段是全文件长，不是本块长——只跳 headerSize 继续扫子块
                RES_XML_TYPE -> { off += headerSize; continue }
                RES_XML_START_ELEMENT_TYPE -> {
                    if (pkg == null) {
                        val nsIdx = le32(bytes, off + headerSize)          // attrExt.ns
                        val nameIdx = le32(bytes, off + headerSize + 4)    // attrExt.name
                        val name = strings.getOrNull(nameIdx) ?: ""
                        val attrStart = le16(bytes, off + headerSize + 8)
                        val attrSize = le16(bytes, off + headerSize + 10)
                        val attrCount = le16(bytes, off + headerSize + 12)
                        if (name == "manifest") {
                            val base = off + headerSize + attrStart
                            for (i in 0 until attrCount) {
                                val a = base + i * attrSize
                                val aNsIdx = le32(bytes, a)
                                val aNameIdx = le32(bytes, a + 4)
                                val rawValueIdx = le32(bytes, a + 8)
                                val valueType = bytes[a + 15].toInt() and 0xFF
                                val valueData = le32(bytes, a + 16)
                                val aNs = strings.getOrNull(aNsIdx).orEmpty()
                                val aName = strings.getOrNull(aNameIdx) ?: continue
                                if (aNs != ANDROID_NS && aName != "package") continue
                                when (aName) {
                                    "package" -> pkg = rawString(bytes, strings, rawValueIdx, valueData)
                                    "versionCode" -> if (valueType == 0x10) vCode = valueData.toLong() and 0xFFFFFFFFL
                                    "versionName" -> vName = typedString(bytes, strings, valueType, rawValueIdx, valueData)
                                    "compileSdkVersion" -> compileSdk = typedString(bytes, strings, valueType, rawValueIdx, valueData)
                                    "compileSdkVersionCodename" -> compileSdkCode = typedString(bytes, strings, valueType, rawValueIdx, valueData)
                                }
                            }
                        }
                    }
                }
            }
            off += size
        }
        return ManifestInfo(pkg, vName, vCode, compileSdk, compileSdkCode, chunkCount, strings.size)
    }

    /** 从 APK 文件直接解析（找 AndroidManifest.xml 条目）。 */
    fun parseFromApk(file: java.io.File): ManifestInfo? {
        val zip = java.util.zip.ZipFile(file)
        return zip.use { z ->
            val entry = z.getEntry("AndroidManifest.xml") ?: return@use null
            parse(z.getInputStream(entry))
        }
    }

    // ---------- 串池（UTF-8/UTF-16 两格式都认） ----------

    private fun parseStringPool(b: ByteArray, off: Int): List<String> {
        val headerSize = le16(b, off + 2)
        val stringCount = le32(b, off + 8)
        val flags = le32(b, off + 16)
        val stringsStart = le32(b, off + 20)
        val isUtf8 = (flags and (1 shl 8)) != 0
        val out = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val offsetEntry = off + headerSize + i * 4
            if (offsetEntry + 4 > b.size) break
            val dataOff = off + stringsStart + le32(b, offsetEntry)
            out += if (isUtf8) readUtf8(b, dataOff) else readUtf16(b, dataOff)
        }
        return out
    }

    private fun readUtf8(b: ByteArray, p: Int): String {
        var i = p
        if (i >= b.size) return ""
        // u16len（可能两字节），u8len（可能两字节），然后数据到 \0
        if (b[i].toInt() and 0x80 != 0) i += 2 else i += 1
        var len = 0
        if (i < b.size && b[i].toInt() and 0x80 != 0) { len = ((b[i].toInt() and 0x7F) shl 8) or (b[i + 1].toInt() and 0xFF); i += 2 } else if (i < b.size) { len = b[i].toInt() and 0xFF; i += 1 }
        if (i + len > b.size) len = (b.size - i).coerceAtLeast(0)
        return String(b, i, len, Charsets.UTF_8)
    }

    private fun readUtf16(b: ByteArray, p: Int): String {
        var i = p
        if (i + 2 > b.size) return ""
        var len = le16(b, i)
        i += 2
        if (len and 0x8000 != 0) { len = ((len and 0x7FFF) shl 16) or (if (i + 2 <= b.size) le16(b, i) else 0); i += 2 }
        if (i + len * 2 > b.size) len = ((b.size - i) / 2).coerceAtLeast(0)
        val chars = CharArray(len)
        for (k in 0 until len) chars[k] = le16(b, i + k * 2).toChar()
        return String(chars)
    }

    private fun rawString(b: ByteArray, strings: List<String>, rawIdx: Int, poolIdx: Int): String? {
        if (rawIdx != 0xFFFFFFFFL.toInt() && rawIdx >= 0) return strings.getOrNull(rawIdx)
        return strings.getOrNull(poolIdx)
    }

    private fun typedString(b: ByteArray, strings: List<String>, valueType: Int, rawIdx: Int, data: Int): String? =
        when (valueType) {
            0x03 -> strings.getOrNull(data)                       // STRING
            0x10 -> data.toString()                               // INT_DEC
            0x11 -> "0x" + Integer.toHexString(data)              // INT_HEX
            0x12 -> (data != 0).toString()                        // BOOLEAN
            else -> rawString(b, strings, rawIdx, data)
        }

    private fun le16(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or (((b.getOrNull(p + 1)?.toInt() ?: 0) and 0xFF) shl 8)

    private fun le32(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or (((b.getOrNull(p + 1)?.toInt() ?: 0) and 0xFF) shl 8) or
            (((b.getOrNull(p + 2)?.toInt() ?: 0) and 0xFF) shl 16) or (((b.getOrNull(p + 3)?.toInt() ?: 0) and 0xFF) shl 24)
}
