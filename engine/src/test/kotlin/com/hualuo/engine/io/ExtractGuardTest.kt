package com.hualuo.engine.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * 解压安全闸的行为测试。
 *
 * 测试名全中文且**不带点号**（点号在 Kotlin 反引号方法名里非法，M0 就是这么把编译搞挂的），
 * 这样 CI 日志里能直接读到"哪一条拦住了什么"。每条都对应旧 Agora 真出过的一类事故。
 * 上限一律注入小数值，避免为了测试真造几个 G 的文件。
 *
 * 两轮 CI 白跑的教训，写死在这儿：
 *   1) 断言必须带"实际值"。单参 assertTrue 失败时只给行号，查根因只能再推一轮。
 *   2) `reject` 只准把**闸门的拒绝**当答案。上一版谁抛异常它都收，于是
 *      TemporaryFolder 的 IllegalStateException（同一个名字 newFolder 两次）冒充了"闸门的理由"，
 *      4 条测试红成"盘符/UNC/双层编码没拦住"，而闸门其实全对（见对照表 run 34690750236）。
 *   3) 一个测试里要反复用根目录，就用 lazy 拿同一个，别每次 newFolder 一个新名字。
 *
 * 断言共 31 条（本文件自己的上限约定：再加就拆文件，别堆成一坨）。
 */
class ExtractGuardTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** 一个测试一个根目录；反复取都是同一个目录（JUnit4 每个测试方法一个新实例）。 */
    private val rootDir: File by lazy { folder.newFolder("包根目录") }

    /** 只接受闸门给出的拒绝；其它异常当场判"脚手架炸了"，不许冒充安全逻辑的结论。 */
    private fun reject(what: String, block: () -> Unit): String =
        rejectWhere(what, { it is ExtractReject }, block)

    /** 参数校验类（ExtractLimits 写歪、字节数为负）走的是 IllegalArgumentException。 */
    private fun rejectIllegalArgument(what: String, block: () -> Unit): String =
        rejectWhere(what, { it is IllegalArgumentException }, block)

    private fun rejectWhere(what: String, expected: (Throwable) -> Boolean, block: () -> Unit): String {
        val error = runCatching(block).exceptionOrNull()
            ?: throw AssertionError("本该被拒绝却安静通过了：$what")
        if (!expected(error)) {
            throw AssertionError(
                "测「$what」时抛的是 ${error::class.simpleName}（${error.message}），不是闸门的拒绝：" +
                    "脚手架或前置准备炸了，别把它当成安全逻辑的结论",
            )
        }
        return error.message ?: "拒绝消息是空的，这本身就算不合格：$what"
    }

    @Test
    fun `条目名想跳出目标目录必须被拒绝`() {
        val message = reject("../逃出去的文件.bin") { normalizeEntryPath(rootDir, "../逃出去的文件.bin") }
        assertTrue("消息要说清为什么拦，实际是：$message", message.contains(".."))
    }

    @Test
    fun `绝对路径条目名必须被拒绝`() {
        val message = reject("/etc/hosts") { normalizeEntryPath(rootDir, "/etc/hosts") }
        assertTrue("要说是绝对路径，实际是：$message", message.contains("绝对路径"))
    }

    @Test
    fun `反斜杠伪装和盘符写法也拦得住`() {
        val backslash = reject("..\\..\\w.exe") { normalizeEntryPath(rootDir, "..\\..\\w.exe") }
        assertTrue("反斜杠穿越必须认出，实际是：$backslash", backslash.contains(".."))
        val drive = reject("C:/Windows/x.bin") { normalizeEntryPath(rootDir, "C:/Windows/x.bin") }
        assertTrue("盘符写法必须被拦，实际是：$drive", drive.contains("盘符"))
        val pipe = reject("C|/Windows/x.bin") { normalizeEntryPath(rootDir, "C|/Windows/x.bin") }
        assertTrue("盘符变体 C| 必须被拦，实际是：$pipe", pipe.contains("盘符"))
    }

    @Test
    fun `百分号编码藏住的穿越拦得住且双层编码也拦得住`() {
        val single = reject("%2e%2e%2f坏东西.bin") { normalizeEntryPath(rootDir, "%2e%2e%2f坏东西.bin") }
        assertTrue("单层还原后必须认出越界，实际是：$single", single.contains(".."))
        val double = reject("%252e%252e%252f坏东西.bin") {
            normalizeEntryPath(rootDir, "%252e%252e%252f坏东西.bin")
        }
        assertTrue("双层还原后必须认出越界，实际是：$double", double.contains(".."))
    }

    @Test
    fun `中文条目名被百分号编码时能还原`() {
        assertEquals("中文 说明.txt", percentDecodeName("%E4%B8%AD%E6%96%87%20%E8%AF%B4%E6%98%8E.txt"))
        // 文件名里的加号是字面量，不能被当成空格（那是表单规则的锅）
        assertEquals("a+b.bin", percentDecodeName("a+b.bin"))
        // 半截百分号不许抛，保留原样，由后面的校验决定去留
        assertEquals("坏%zz名", percentDecodeName("坏%zz名"))
    }

    @Test
    fun `正常子目录能落在根目录里面`() {
        val dest = normalizeEntryPath(rootDir, "./子包//再下一层/文件.bin")
        assertEquals("文件.bin", dest.name)
        assertTrue("落点必须还在根目录里：${dest.path}", dest.path.startsWith(rootDir.path))
        assertEquals("根下面应该是三层名字", 3, segmentsUnderRoot(dest, rootDir))
    }

    @Test
    fun `反斜杠开头与网络共享路径都算绝对路径`() {
        val backslashRoot = reject("\\\\服务器\\共享\\文件.bin") {
            normalizeEntryPath(rootDir, "\\\\服务器\\共享\\文件.bin")
        }
        assertTrue("反斜杠开头必须算绝对路径，实际是：$backslashRoot", backslashRoot.contains("反斜杠开头"))
        val unc = reject("//服务器/共享/文件.bin") { normalizeEntryPath(rootDir, "//服务器/共享/文件.bin") }
        assertTrue("正斜杠 UNC 要专门说清，实际是：$unc", unc.contains("网络共享"))
    }

    @Test
    fun `中间层是符号链接时也要拦住`() {
        val outside = folder.newFolder("外面的目录")
        val link = File(rootDir, "链接目录")
        runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }
            .onFailure { return } // 文件系统不支持符号链接就跳过，不算失败也不算验过
        val message = reject("链接目录/写出去的文件.bin") {
            normalizeEntryPath(rootDir, "链接目录/写出去的文件.bin")
        }
        assertTrue("符号链接越界必须被 canonical 复核拦住，实际是：$message", message.contains("外面"))
    }

    @Test
    fun `异常消息里的原始名控制字符可视化且不截断`() {
        // 零脱敏纪律：内容一字不改——只把控制字符（换行等）可视化成点防伪造日志行；
        // 长名不再截断（截断=丢数据）。
        val withNewline = reject("点点加换行") { normalizeEntryPath(rootDir, "..\n伪造一行日志") }
        assertFalse(
            "换行要可视化成点（防伪造日志行），但内容字符一个不少：$withNewline",
            withNewline.contains('\n'),
        )
        val tooLong = reject("三百个x/../坏.bin") {
            normalizeEntryPath(rootDir, "x".repeat(300) + "/../坏.bin")
        }
        assertTrue("300 个 x 原文全在（零截断）：${tooLong.length} 字符", tooLong.count { it == 'x' } >= 300)
    }

    @Test
    fun `条目数超限时报错消息说得清`() {
        val guard = ExtractGuard(ExtractLimits(maxEntries = 2))
        guard.beginEntry(rootDir, "一.bin", 10L).also { guard.accept(5L); guard.endEntry(10L) }
        guard.beginEntry(rootDir, "二.bin", 10L).also { guard.accept(5L); guard.endEntry(10L) }
        val message = reject("第三个条目") { guard.beginEntry(rootDir, "三.bin", 10L) }
        assertTrue("要写明是条目数问题，实际是：$message", message.contains("条目数已达上限"))
        assertEquals(2, guard.entries)
    }

    @Test
    fun `单条目体积超限当场断`() {
        val guard = ExtractGuard(ExtractLimits(maxEntryBytes = 10L, maxTotalBytes = 100L))
        guard.beginEntry(rootDir, "胖条目.bin", 5L)
        guard.accept(6L)
        val message = reject("胖条目继续吃") { guard.accept(5L) }
        assertTrue("要写明是单条目体积，实际是：$message", message.contains("单条目解压后已达 11 字节"))
    }

    @Test
    fun `整包总量超限当场断`() {
        val guard = ExtractGuard(ExtractLimits(maxEntryBytes = 10L, maxTotalBytes = 12L))
        guard.beginEntry(rootDir, "第一条.bin", 5L).also { guard.accept(10L); guard.endEntry(5L) }
        guard.beginEntry(rootDir, "第二条.bin", 5L)
        val message = reject("第二条继续吃") { guard.accept(3L) }
        assertTrue("要写明是总量，实际是：$message", message.contains("整包解压后已达 13 字节"))
        assertEquals("越限时账已经记上了，调用方据此清理已写出的部分", 13L, guard.decompressedTotal)
    }

    @Test
    fun `压缩比超限当场断`() {
        val guard = ExtractGuard(ExtractLimits(maxEntryBytes = 100L, maxTotalBytes = 100L, maxRatio = 2.0))
        guard.beginEntry(rootDir, "炸弹.bin", 10L)
        guard.accept(30L)
        val message = reject("炸弹收尾") { guard.endEntry(10L) }
        assertTrue("要给出倍数和两边字节数，实际是：$message", message.contains("压缩比 3.0 超过上限 2.0"))
    }

    @Test
    fun `零字节条目不会被误判成炸弹`() {
        val guard = ExtractGuard(ExtractLimits(maxEntryBytes = 10L, maxTotalBytes = 10L, maxRatio = 2.0))
        guard.beginEntry(rootDir, "空目录条目/", 0L)
        guard.accept(0L)
        guard.endEntry(0L) // 目录条目压缩前后都是零，不该抛
        assertEquals(0L, guard.decompressedTotal)
    }

    @Test
    fun `上限写零或负数当场就被拦住`() {
        val entries = rejectIllegalArgument("maxEntries=0") { ExtractLimits(maxEntries = 0) }
        assertTrue("要写明条目数上限，实际是：$entries", entries.contains("条目数上限必须大于零"))
        val single = rejectIllegalArgument("maxEntryBytes=-1") { ExtractLimits(maxEntryBytes = -1L) }
        assertTrue("要写明单条目上限，实际是：$single", single.contains("单条目上限必须大于零"))
        val ratio = rejectIllegalArgument("maxRatio=0.5") { ExtractLimits(maxRatio = 0.5) }
        assertTrue("要写明压缩比下限，实际是：$ratio", ratio.contains("压缩比上限必须大于一"))
        val inverted = rejectIllegalArgument("总量小于单条目") {
            ExtractLimits(maxEntryBytes = 100L, maxTotalBytes = 50L)
        }
        assertTrue("两边写反时必须点名关系，实际是：$inverted", inverted.contains("不能小于单条目上限"))
    }

    @Test
    fun `记账字节数为负要报参数错`() {
        val guard = ExtractGuard()
        guard.beginEntry(rootDir, "正常.bin", 5L)
        val message = rejectIllegalArgument("accept 负数") { guard.accept(-1L) }
        assertTrue("要说是字节数不能为负，实际是：$message", message.contains("字节数不能为负"))
    }

    /** 数一下落点在根目录之下有几层名字（斜杠分隔）。 */
    private fun segmentsUnderRoot(dest: File, root: File): Int =
        dest.absolutePath.removePrefix(root.absolutePath)
            .trim(File.separatorChar)
            .split(File.separatorChar)
            .count { it.isNotEmpty() }
}
