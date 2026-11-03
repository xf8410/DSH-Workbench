package com.hualuo.engine.apk

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * APK 签名证书摘要（560 清单 128）：定位 APK Signing Block，抽 v2/v3 签名方案里的
 * 证书链，给 subject + SHA-256 指纹——覆盖安装核对（签名不一致装不上）就靠这个账。
 *
 * 格式：EOCD 定位中央目录，看中央目录前 16 字节 magic "APK Sig Block 42"，再
 * 遍历 ID-value 对（v2=0x7109871a，v3=0xf05368c0），长度前缀结构里抽证书 DER。
 * 全程 RandomAccessFile 按块读（红线二），证书链 DER 用流式 CertificateFactory 解。
 */
object ApkSignature {

    private val APK_SIG_BLOCK_MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
    private const val V2_ID = 0x7109871a
    private val V3_ID = 0xf05368c0.toInt() // 位模式同 le32At 的 Int（0xf05368c0 超 Int.MAX，字面量会升 Long）

    /** 一份签名方案账。 */
    data class SchemeInfo(
        val scheme: String,
        val certSubject: String,
        val certSha256Hex: String,
        val certCount: Int,
    )

    data class SignatureReport(
        val schemes: List<SchemeInfo>,
        val v2Present: Boolean,
        val v3Present: Boolean,
        val error: String?,
    )

    fun inspect(file: File): SignatureReport {
        val schemes = ArrayList<SchemeInfo>()
        var err: String? = null
        try {
            RandomAccessFile(file, "r").use { raf ->
                val eocd = findEocd(raf) ?: return SignatureReport(schemes, false, false, "找不到 ZIP 结束记录（EOCD）：不是标准 ZIP/APK")
                val cdOffset = le32At(raf, eocd + 16).toLong()
                if (cdOffset < 32L) return SignatureReport(schemes, false, false, "中央目录前没有签名块空间：无 v2/v3 签名（只有 v1 jar 签名或未签名）")
                // 签名块：magic 在 cdOffset-16，sizeOfBlock 在 magic 前 8 字节，块起点 = cdOffset - sizeOfBlock - 8
                val magic = ByteArray(16)
                raf.seek(cdOffset - 16L)
                raf.readFully(magic)
                if (!magic.contentEquals(APK_SIG_BLOCK_MAGIC)) {
                    return SignatureReport(schemes, false, false, "中央目录前没有 APK Signing Block：只有 v1（jar）签名或未签名")
                }
                // sizeOfBlock（尾 size 字段，@cdOffset-24）= pairs 段长、尾size(8)、magic(16) 三者相加
                // pairs 段起点 = cdOffset - sizeOfBlock（同时是头 size 字段的下一字节）
                val sizeOfBlock = le64At(raf, cdOffset - 24)
                val pairsStart = cdOffset - sizeOfBlock
                val pairsEnd = cdOffset - 24
                var p = pairsStart
                while (p + 12 <= pairsEnd) {
                    // pair = uint64 长度（含 id 4B 与 value）+ uint32 id + value
                    val pairLen = le64At(raf, p)
                    val id = le32At(raf, p + 8)
                    val bodyStart = p + 12
                    val bodyLen = (pairLen - 4).toInt()
                    if (bodyLen <= 0 || bodyStart + bodyLen > file.length()) break
                    when (id) {
                        V2_ID -> parseScheme(raf, bodyStart, bodyLen, "v2")?.let { schemes += it }
                        V3_ID -> parseScheme(raf, bodyStart, bodyLen, "v3")?.let { schemes += it }
                    }
                    p += 8 + pairLen
                }
            }
        } catch (e: Exception) {
            err = "签名块读取失败：${e.message ?: e::class.java.simpleName}"
        }
        return SignatureReport(schemes, schemes.any { it.scheme == "v2" }, schemes.any { it.scheme == "v3" }, err)
    }

    /** v2/v3 signed-data：lengthPrefixed{ signed{ digests, certificates, ... }, signatures, publicKey }——抽 certificates。 */
    private fun parseScheme(raf: RandomAccessFile, off: Long, len: Int, scheme: String): SchemeInfo? {
        val buf = ByteArray(len)
        raf.seek(off)
        raf.readFully(buf)
        var p = 0
        // 外层 lengthPrefixed（signed data）
        val signedLen = le32(buf, p)
        val signedEnd = p + 4 + signedLen
        p += 4
        // signed data 内：先 lengthPrefixed(digests) 再 lengthPrefixed(certificates)
        if (p + 4 > buf.size) return null
        val digestsLen = le32(buf, p); p += 4 + digestsLen
        if (p + 4 > buf.size) return null
        val certsLen = le32(buf, p); p += 4
        val certsEnd = minOf(p + certsLen, buf.size)
        val certFactory = CertificateFactory.getInstance("X509")
        var first: X509Certificate? = null
        var count = 0
        while (p + 4 <= certsEnd) {
            val certLen = le32(buf, p); p += 4
            if (certLen <= 0 || p + certLen > buf.size) break
            val cert = runCatching {
                certFactory.generateCertificate(java.io.ByteArrayInputStream(buf, p, certLen)) as X509Certificate
            }.getOrNull() ?: continue
            if (first == null) first = cert
            count++
            p += certLen
        }
        val c = first ?: return null
        val fp = MessageDigest.getInstance("SHA-256").digest(c.encoded)
        return SchemeInfo(scheme, c.subjectX500Principal.name, fp.joinToString("") { String.format("%02x", it) }, count)
    }

    private fun findEocd(raf: RandomAccessFile): Long? {
        val len = raf.length()
        val window = (minOf(len, 65557L)).toInt()
        val buf = ByteArray(window)
        raf.seek(len - window)
        raf.readFully(buf)
        for (i in window - 22 downTo 0) {
            if (buf[i] == 0x50.toByte() && buf[i + 1] == 0x4B.toByte() && buf[i + 2] == 0x05.toByte() && buf[i + 3] == 0x06.toByte()) {
                return len - window + i
            }
        }
        return null
    }

    private fun le32At(raf: RandomAccessFile, p: Long): Int {
        raf.seek(p)
        val b = ByteArray(4); raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun le64At(raf: RandomAccessFile, p: Long): Long {
        raf.seek(p)
        val b = ByteArray(8); raf.readFully(b)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    private fun le32(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
}
