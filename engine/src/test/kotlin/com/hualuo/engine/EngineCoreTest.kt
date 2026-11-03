package com.hualuo.engine

import com.hualuo.engine.io.streamingCopy
import com.hualuo.engine.version.parseVersionProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 测试名字用中文写：CI 日志里直接看得到"过了什么、没过什么"，不用猜英文缩写。
 * 注意：反引号里的名字**不能有点号**（Kotlin 会报 Name contains illegal characters）。
 * 上一版把测试写成「仓库根目录的 version.properties 真的存在」，中间那个点让编译直接挂，
 * CI 白跑一轮 —— 所以这里一律把带点的英文词换成中文说法。
 * 这里跑的是纯逻辑，不需要手机、不需要模拟器。
 */
class EngineCoreTest {

    @Test
    fun `搬一小段内容能算出正确的字节数和校验和`() {
        val input = ByteArrayInputStream("abc".toByteArray(Charsets.UTF_8))
        val output = ByteArrayOutputStream()
        val report = streamingCopy(input, output, totalBytes = 3L)
        assertEquals(3L, report.bytes)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", report.sha256Hex)
        assertEquals("abc", output.toString(Charsets.UTF_8))
    }

    @Test
    fun `空内容不报错且字节数为零`() {
        val report = streamingCopy(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        assertEquals(0L, report.bytes)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", report.sha256Hex)
    }

    @Test
    fun `大内容只占一块缓冲区且进度递增最后等于总量`() {
        val size = 300_000
        val payload = ByteArray(size) { (it % 251).toByte() }
        val progress = mutableListOf<Long>()
        val report = streamingCopy(
            ByteArrayInputStream(payload),
            ByteArrayOutputStream(),
            totalBytes = size.toLong(),
            bufferSize = 8 * 1024,
            onProgress = { done, _ -> progress.add(done) },
        )
        assertEquals(size.toLong(), report.bytes)
        assertTrue("进度必须一路往上加", progress.zipWithNext().all { (a, b) -> b > a })
        assertEquals("进度最后一块必须正好等于总量", size.toLong(), progress.last())
        assertEquals("每块八 KiB 应该搬三十七次（最后一次不满）", 37, progress.size)
    }

    @Test
    fun `缓冲区大小写零或负数要当场拦住`() {
        val error = runCatching {
            streamingCopy(ByteArrayInputStream("x".toByteArray()), ByteArrayOutputStream(), bufferSize = 0)
        }.exceptionOrNull()
        assertTrue("没拦住就是没做参数检查", error is IllegalArgumentException)
    }

    @Test
    fun `版本号能正常读出来`() {
        val version = parseVersionProperties("# 注释\nversionName=0.1.0\nversionCode=1\n")
        assertEquals("0.1.0", version.versionName)
        assertEquals(1, version.versionCode)
        assertEquals("0.1.0（代号 1）", version.display())
    }

    @Test
    fun `版本号写错时必须报错而不是偷偷用默认值`() {
        assertTrue("缺版本号名要报错", errorTextOf { parseVersionProperties("versionCode=2") } != null)
        assertTrue("代号写成中文要报错", errorTextOf { parseVersionProperties("versionName=1.0\nversionCode=一点零") } != null)
        assertTrue("代号写成零要报错", errorTextOf { parseVersionProperties("versionName=1.0\nversionCode=0") } != null)
    }

    @Test
    fun `仓库根目录的版本号文件真的存在且读得通`() {
        // 从测试的工作目录往上找到仓库根；找不到就红。防止有人把版本文件删了还一切正常。
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "version.properties").isFile) {
            dir = dir.parentFile
        }
        val root = dir ?: error("找不到仓库根目录里的版本号文件")
        val version = parseVersionProperties(File(root, "version.properties").readText(Charsets.UTF_8))
        assertTrue("版本名不能为空", version.versionName.isNotEmpty())
        assertTrue("代号必须大于零", version.versionCode > 0)
    }

    private fun errorTextOf(block: () -> Unit): String? =
        runCatching(block).exceptionOrNull()?.message
}
