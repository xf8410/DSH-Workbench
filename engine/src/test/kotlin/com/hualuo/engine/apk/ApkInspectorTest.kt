package com.hualuo.engine.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * APK 检查件对照表：测试里用 ZipOutputStream 造真 APK 形状（全离线）。
 */
class ApkInspectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun buildApk(name: String, entries: List<Triple<String, ByteArray, Int?>>): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { out ->
            entries.forEach { (path, data, level) ->
                val e = ZipEntry(path)
                if (level == null) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = java.util.zip.CRC32().apply { update(data) }.value
                }
                if (level != null) out.setLevel(level)
                out.putNextEntry(e)
                out.write(data)
                out.closeEntry()
            }
        }
        return f
    }

    private val dex = ("dex\n035" + '\u0000').toByteArray()
    private val so = ByteArray(64) { it.toByte() }
    private val arsc = "arsc-ish".toByteArray()

    @Test
    fun magicAndEntriesListed() {
        val apk = buildApk(
            "base.apk",
            listOf(
                Triple("AndroidManifest.xml", arsc, Deflater.BEST_SPEED),
                Triple("classes.dex", dex, Deflater.BEST_SPEED),
                Triple("res/values.arsc", arsc, null),
            ),
        )
        val r = ApkInspector.inspect(apk)
        assertTrue("ZIP 魔数成立：${r.magicNote}", r.magicOk)
        assertEquals(3, r.entries.size)
        val names = r.entries.map { it.name }
        assertTrue(names.contains("classes.dex"))
        val dexEntry = r.entries.first { it.name == "classes.dex" }
        assertEquals("Deflate", dexEntry.method)
        assertEquals("存储", r.entries.first { it.name == "res/values.arsc" }.method)
        assertTrue("CRC 十六进制在账：", dexEntry.crcHex.isNotBlank())
        assertEquals(apk.length(), r.sizeBytes)
        assertTrue(r.totalUncompressedBytes > 0)
        assertTrue("SHA-256 六十四位：", r.sha256Hex.length == 64)
    }

    @Test
    fun abiSplitsDetectedFromLibPaths() {
        val apk = buildApk(
            "split_config.arm64_v8a.apk",
            listOf(
                Triple("classes.dex", dex, Deflater.BEST_SPEED),
                Triple("lib/arm64-v8a/libgame.so", so, Deflater.BEST_SPEED),
                Triple("lib/armeabi-v7a/libgame.so", so, Deflater.BEST_SPEED),
            ),
        )
        val r = ApkInspector.inspect(apk)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), r.abis)
    }

    @Test
    fun duplicateCrcGrouped() {
        val apk = buildApk(
            "dup.apk",
            listOf(
                Triple("a/assets/same.bin", arsc, null),
                Triple("b/assets/same.bin", arsc, null),
                Triple("c/assets/other.bin", "different".toByteArray(), null),
            ),
        )
        val r = ApkInspector.inspect(apk)
        assertEquals(1, r.duplicateGroups.size)
        assertEquals(2, r.duplicateGroups[0].size)
        assertTrue(r.duplicateGroups[0].all { it.endsWith("same.bin") })
    }

    @Test
    fun noAbiWhenNoLibDir() {
        val apk = buildApk("plain.apk", listOf(Triple("classes.dex", dex, Deflater.BEST_SPEED)))
        assertTrue(ApkInspector.inspect(apk).abis.isEmpty())
    }

    @Test
    fun notAnApkStillReportsWithMagicDown() {
        val junk = File(tmp.root, "junk.apk")
        junk.writeBytes("this is not a zip at all, just some text".toByteArray())
        val r = ApkInspector.inspect(junk)
        assertTrue("魔数不成立也要出账：", !r.magicOk)
        assertTrue("说明里讲清楚：", r.magicNote.isNotBlank())
        // 不是 ZIP，ZipFile 会炸——inspect 得优雅降级给空账
        assertTrue(r.entries.isEmpty())
        assertTrue(r.sha256Hex.length == 64)
    }
}
