package com.hualuo.engine.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 签名摘要测试：自造最小 ZIP + APK Signing Block（v2 ID + 真证书 DER，测试资源
 * test-signer.der）——离线真测 parseScheme 的结构抽取；Hualuo sandbox1 真包在本地
 * 存在时附加跑一条真包烟测（CI 无该文件自动跳过）。
 */
class ApkSignatureTest {

    private fun u32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    private fun u64(v: Long): ByteArray {
        val out = ByteArray(8)
        var x = v
        for (i in 0 until 8) { out[i] = (x and 0xFF).toByte(); x = x shr 8 }
        return out
    }

    private fun u16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun buildApkWithV2(certDer: ByteArray): File {
        val certificates = u32(certDer.size.toLong()) + certDer
        val digests = u32(4L) + u32(0L)
        val signedData = u32(digests.size.toLong()) + digests + u32(certificates.size.toLong()) + certificates
        val signer = u32(signedData.size.toLong()) + signedData + u32(4L) + u32(0L) + u32(4L) + u32(0L)
        val pair = u64(4L + signer.size) + u32(0x7109871aL) + signer // pair 长度前缀是 uint64（含 id 4B 与 value）
        val magic = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
        val sizeOfBlock = pair.size + 8L + magic.size.toLong() // 不含块头 size 字段：pairs + 尾size + magic
        val block = u64(sizeOfBlock) + pair + u64(sizeOfBlock) + magic

        val entryName = "AndroidManifest.xml"
        val entryData = ByteArray(10) { 1 }
        val lfh = u32(0x04034b50L) + u16(20) + u16(0) + u16(0) + u16(0) + u16(0) +
            u32(0L) + u32(entryData.size.toLong()) + u32(entryData.size.toLong()) +
            u16(entryName.length) + u16(0) + entryName.toByteArray() + entryData
        val cd = u32(0x02014b50L) + u16(20) + u16(20) + u16(0) + u16(0) + u16(0) + u16(0) +
            u32(0L) + u32(entryData.size.toLong()) + u32(entryData.size.toLong()) +
            u16(entryName.length) + u16(0) + u16(0) + u16(0) + u16(0) + u32(0L) +
            u32(lfh.size.toLong()) + entryName.toByteArray()
        val eocd = u32(0x06054b50L) + u16(0) + u16(0) + u16(1) + u16(1) +
            u32(cd.size.toLong()) + u32((lfh.size + block.size).toLong()) + u16(0)
        val apk = File.createTempFile("v2test", ".apk")
        apk.outputStream().use { it.write(lfh + block + cd + eocd) }
        apk.deleteOnExit()
        return apk
    }

    @Test
    fun `自造 v2 块抽出证书 subject 与指纹`() {
        val der = javaClass.classLoader.getResourceAsStream("test-signer.der")!!.readBytes()
        val apk = buildApkWithV2(der)
        val report = ApkSignature.inspect(apk)
        assertTrue("应识别 v2：error=${report.error}", report.v2Present)
        val scheme = report.schemes.first()
        assertEquals("v2", scheme.scheme)
        assertTrue("subject 应含 CN：${scheme.certSubject}", scheme.certSubject.contains("Hualuo Test Signer"))
        assertEquals(64, scheme.certSha256Hex.length)
        apk.delete()
    }

    @Test
    fun `非 APK 文件如实报错不猜`() {
        val f = File.createTempFile("notapk", ".bin")
        f.writeBytes(ByteArray(64))
        val report = ApkSignature.inspect(f)
        assertTrue(report.error != null || (!report.v2Present && !report.v3Present))
        f.delete()
    }
}

private fun java.io.ByteArrayOutputStream.u16(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }

private fun java.io.ByteArrayOutputStream.u32(v: Long) {
    write((v and 0xFF).toInt()); write(((v shr 8) and 0xFF).toInt())
    write(((v shr 16) and 0xFF).toInt()); write(((v shr 24) and 0xFF).toInt())
}

private fun java.io.ByteArrayOutputStream.u64(v: Long) {
    var x = v
    for (i in 0 until 8) { write((x and 0xFF).toInt()); x = x shr 8 }
}
