package com.hualuo.engine.settings

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 设置存储的纯 JVM 测试（不用手机、不用安卓）。测试名一律 ASCII，说明和断言消息用中文。
 *
 * 覆盖面按旧仓踩过的坑排：内容必须逐字往返、未知键不许丢、坏数据必须出声、
 * 写失败不许报成功、大文件不许整份吞进内存、半个表情不许变成烂字节。
 *
 * 源文件受"不许表情（转义也不行）"闸门管：测试样本需要真表情码位时，
 * 用码位构造字符串（Character.toChars），不在源码里写那个字符本身。
 */
class SettingsStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class MemoryStorage(initial: String? = null) : SettingsStorage {
        var text: String? = initial
        var writeCount = 0
        var failWith: String? = null

        override fun read(): String? = text

        override fun write(text: String) {
            writeCount += 1
            failWith?.let { throw IOException(it) }
            this.text = text
        }
    }

    private fun storeOf(text: String? = null): SettingsStore =
        SettingsStore(MemoryStorage(text))

    @Test
    fun roundTripsTypedValuesInMemory() {
        val store = storeOf()
        store.setString("model.current", "deepseek-r1")
        store.setInt("tool.idle_timeout_seconds", 300)
        store.setLong("data.max_total_mb", 400L)
        store.setDouble("generate.temperature", 0.7)
        store.setBoolean("tools.web_search_enabled", true)

        assertTrue(store.save().persisted)
        val reopened = storeOf(store.text())

        assertEquals("deepseek-r1", reopened.string("model.current"))
        assertTrue("键该在", reopened.has("tool.idle_timeout_seconds"))
        assertEquals(300, reopened.int("tool.idle_timeout_seconds", MISSING))
        assertEquals(400L, reopened.long("data.max_total_mb", MISSING_LONG))
        assertEquals(0.7, reopened.double("generate.temperature", 0.0), 0.0)
        assertTrue(reopened.boolean("tools.web_search_enabled", false))
        assertTrue("往返不该留坏消息：${reopened.issues().map { it.detail }}", reopened.issues().isEmpty())
    }

    @Test
    fun controlCharactersRoundTripExactly() {
        val tricky = "两行\n第二行\r\n制表符\t结束\\反斜杠\u0000\u001F尾巴"
        val store = storeOf()
        store.setString("system.prompt", tricky)
        store.save()

        val restored = storeOf(store.text())

        assertEquals("控制字符必须逐字还原", tricky, restored.string("system.prompt"))
        assertFalse("转义后不该出现裸换行", store.text().contains("两行\n"))
    }

    @Test
    fun surrogatePairRoundTripsAsPlainText() {
        // 码位构造，不进源码：U+2705 对勾表情、U+1F600 大笑脸（合法代理对）。
        val checkMark = String(Character.toChars(0x2705))
        val bigGrin = String(Character.toChars(0x1F600))
        val sample = "收到 $checkMark 开工 $bigGrin"
        val store = storeOf()
        store.setString("draft.text", sample)
        store.drainIssues()

        assertTrue("正常表情不该被转义成乱码", store.text().contains(bigGrin))
        val reopened = storeOf(store.text())
        assertEquals(sample, reopened.string("draft.text"))
        assertTrue("正常表情不该出声：${reopened.issues().map { it.detail }}", reopened.issues().isEmpty())
    }

    @Test
    fun loneHighSurrogateIsEscapedAndVoiced() {
        val half = "前半\uD83D后半"
        val store = storeOf()
        store.setString("draft.text", half)

        assertEquals(1, store.issues().count { it is SettingsIssue.LoneSurrogate })
        assertFalse("半个表情不许以裸码元形式进文件", store.text().contains("\uD83D"))
        assertTrue("必须转义存起来", store.text().contains("\\ud83d"))
        assertTrue(store.save().persisted)

        val reopened = storeOf(store.text())
        assertEquals("读回来还得逐字相同（不许悄悄修成别的）", half, reopened.string("draft.text"))
        assertEquals("再读一次同样要出声", 1, reopened.issues().count { it is SettingsIssue.LoneSurrogate })
    }

    @Test
    fun loneLowSurrogateIsAlsoCaught() {
        val store = storeOf("draft.text=尾巴\uDC00占位")

        assertEquals(1, store.issues().count { it is SettingsIssue.LoneSurrogate })
        assertEquals("尾巴\uDC00占位", store.string("draft.text"))
    }

    @Test
    fun unicodeEscapeDecodesToSameChar() {
        val store = storeOf("system.prompt=换行符到\\u000a结束\n")

        assertEquals("换行符到\n结束", store.string("system.prompt"))
    }

    @Test
    fun unknownKeysArePreservedAcrossSave() {
        val original = "#format=1\nfuture.brand_new_switch=true\nmodel.current=abc\n"
        val store = storeOf(original)
        store.setString("model.current", "xyz")
        store.save()

        val restored = storeOf(store.text())

        assertEquals("未知键必须原样留回", "true", restored.string("future.brand_new_switch"))
        assertEquals("xyz", restored.string("model.current"))
    }

    @Test
    fun illegalKeyNameIsKeptButReported() {
        val store = storeOf("BadKey=1\n")

        assertEquals("1", store.string("BadKey"))
        assertEquals(1, store.issues().count { it is SettingsIssue.IllegalKey })
        store.save()
        assertTrue("不合规键名也要写回，不许偷偷删", store.text().contains("BadKey=1"))
    }

    @Test
    fun malformedLinesAreReportedNotSwallowed() {
        val store = storeOf("no-equals-here\n=missing-key\n")

        assertEquals(1, store.issues().count { it is SettingsIssue.MissingEquals })
        assertEquals(1, store.issues().count { it is SettingsIssue.EmptyKey })
        assertEquals(0, store.size())
    }

    @Test
    fun duplicateKeyTakesLastAndReports() {
        val store = storeOf("model.current=a\nmodel.current=b\n")

        assertEquals("b", store.string("model.current"))
        val issues = store.issues()
        assertTrue(issues.any { it is SettingsIssue.DuplicateKey })
        assertEquals("只该有重复这一条坏消息：${issues.map { it.detail }}", 1, issues.size)
    }

    @Test
    fun badEscapeStaysLiteralAndReports() {
        // 反斜杠按码位构造（Char(0x5C)），样本内容不依赖源码里的转义层数。
        // 这个坑 CI 抓过：整文件重写时手写转义对多落了一层，落盘样本比断言多一根杠，
        // 测试红了一轮才认账。码位构造让「一根杠」在源码里只有一种写法、没有歧义。
        val bs = Char(0x5C).toString()
        val store = storeOf("model.current=尾巴$bs" + "q\n")

        assertEquals("尾巴$bs" + "q", store.string("model.current"))
        assertEquals(1, store.issues().count { it is SettingsIssue.BadEscape })

        val trailing = storeOf("model.current=斜杠在尾$bs\n")
        assertEquals("斜杠在尾$bs", trailing.string("model.current"))
        assertEquals(1, trailing.issues().count { it is SettingsIssue.BadEscape })
    }

    @Test
    fun unreadableValuesFallBackToDefaultAndReport() {
        val store = storeOf(
            """
            #format=1
            generate.temperature=abc
            tool.idle_timeout_seconds=soon
            data.max_total_mb=many
            tools.web_search_enabled=maybe
            """.trimIndent(),
        )

        assertEquals(0.7, store.double("generate.temperature", 0.7), 0.0)
        assertEquals(300, store.int("tool.idle_timeout_seconds", 300))
        assertEquals(400L, store.long("data.max_total_mb", 400L))
        assertTrue(store.boolean("tools.web_search_enabled", true))
        assertEquals("四个坏值必须四条都出声", 4, store.issues().count { it is SettingsIssue.BadValue })
    }

    @Test
    fun outOfRangeIntClampsAndReports() {
        val store = storeOf("tool.idle_timeout_seconds=99999\n")

        val value = store.intInRange("tool.idle_timeout_seconds", 300, 0, 3600)

        assertEquals(3600, value)
        val issue = store.issues().first { it is SettingsIssue.OutOfRange } as SettingsIssue.OutOfRange
        assertEquals(3600L, issue.clampedTo)
        assertEquals(3600L, issue.max)
    }

    @Test
    fun booleanIsLenientOnReadAndCanonicalOnWrite() {
        val store = storeOf("a=yes\nb=OFF\nc=1\n")

        assertTrue(store.boolean("a", false))
        assertFalse(store.boolean("b", true))
        assertTrue(store.boolean("c", false))

        store.setBoolean("a", store.boolean("a", false))
        store.setBoolean("b", store.boolean("b", true))
        assertTrue(store.text().contains("a=true"))
        assertTrue(store.text().contains("b=false"))
    }

    @Test
    fun newerFormatMarkerOnlyWarns() {
        val store = storeOf("#format=9\nmodel.current=abc\n")

        assertEquals("abc", store.string("model.current"))
        val issue = store.issues().first { it is SettingsIssue.NewerFormat } as SettingsIssue.NewerFormat
        assertEquals(9, issue.declared)
        assertEquals(1, store.issues().size)

        val broken = storeOf("#format=很多\n")
        assertEquals(1, broken.issues().count { it is SettingsIssue.BadFormatMarker })
    }

    @Test
    fun dirtyFlagAndSaveIfDirtyAvoidIdleWrites() {
        val storage = MemoryStorage()
        val store = SettingsStore(storage)

        assertFalse(store.isDirty())
        assertNull(store.saveIfDirty())

        store.setString("model.current", "abc")
        assertTrue(store.isDirty())
        assertSaved(store.saveIfDirty())
        assertFalse(store.isDirty())
        assertNull(store.saveIfDirty())

        store.setString("model.current", "abc")
        assertFalse("写回同一个值不算改动", store.isDirty())

        store.remove("model.current")
        assertTrue(store.isDirty())
        assertNull(store.raw("model.current"))
    }

    private fun assertSaved(result: SaveResult?) {
        assertTrue("本该落盘却没落", result != null && result.persisted)
    }

    @Test
    fun removeKeysCountsOnlyRealRemovals() {
        val store = storeOf("a.one=1\na.two=2\n")

        assertEquals(2, store.removeKeys(listOf("a.one", "a.two", "a.missing")))
        assertEquals(0, store.size())
    }

    @Test
    fun failedSaveIsNeverReportedAsSuccess() {
        val storage = MemoryStorage()
        storage.failWith = "磁盘满了"
        val store = SettingsStore(storage)
        store.setString("model.current", "abc")

        val result = store.save()

        assertFalse(result.persisted)
        assertTrue("失败必须带原因：${result.failure}", result.failure.orEmpty().contains("磁盘满了"))
        assertTrue("落盘失败还得算未保存，脏位不能清", store.isDirty())
    }

    @Test
    fun drainIssuesClearsAccumulated() {
        val store = storeOf("BadKey=1\n")

        assertEquals(1, store.drainIssues().size)
        assertTrue(store.issues().isEmpty())
    }

    @Test
    fun reloadDiscardsUnsavedChanges() {
        val storage = MemoryStorage("model.current=old\n")
        val store = SettingsStore(storage)
        store.setString("model.current", "new")

        store.reload()

        assertEquals("old", store.string("model.current"))
        assertFalse(store.isDirty())
    }

    @Test
    fun illegalKeyNamesAreRejectedAtTheCallSite() {
        val store = storeOf()
        assertIllegal { store.setString("中文键名", "v") }
        assertIllegal { store.setString("UPPER_CASE", "v") }
        assertIllegal { store.setString("", "v") }
        assertIllegal { store.setString(".leading", "v") }
        assertIllegal { store.setString("k".repeat(65), "v") }
    }

    private fun assertIllegal(block: () -> Unit) {
        try {
            block()
            assertTrue("非法键名本该抛异常，结果放过了", false)
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.isNotEmpty())
        }
    }

    @Test
    fun fileBackendWritesAtomicallyAndReadsBack() {
        val file = File(folder.root, "nested/dir/settings.txt")
        val store = SettingsStore(FileSettingsStorage(file))
        store.setString("model.current", "qwen3.8-flash")
        store.setInt("tool.idle_timeout_seconds", 300)

        assertTrue(store.save().persisted)
        assertTrue("文件得真在盘上", file.isFile)
        assertFalse("不该留下临时文件残渣", folder.root.walkTopDown().any { it.name.endsWith(".tmp") })

        val reopened = SettingsStore(FileSettingsStorage(file))
        assertEquals("qwen3.8-flash", reopened.string("model.current"))
        assertEquals(300, reopened.int("tool.idle_timeout_seconds", MISSING))
        assertTrue("正常文件不该有坏消息：${reopened.issues().map { it.detail }}", reopened.issues().isEmpty())
    }

    @Test
    fun fileBackendOverwriteKeepsExactlyOneGoodCopy() {
        val file = File(folder.root, "settings.txt")
        val first = SettingsStore(FileSettingsStorage(file))
        first.setString("model.current", "旧值")
        assertTrue(first.save().persisted)

        val second = SettingsStore(FileSettingsStorage(file))
        assertEquals("旧值", second.string("model.current"))
        second.setString("model.current", "新值")
        assertTrue(second.save().persisted)

        val reopened = SettingsStore(FileSettingsStorage(file))
        assertEquals("改名盖过旧文件后必须读出新值", "新值", reopened.string("model.current"))

        val leftovers = folder.root.listFiles()?.filter { !it.name.endsWith(".tmp") } ?: emptyList()
        assertEquals("目录里只该留一份设置文件：${folder.root.walkTopDown().filter { it.isFile }.map { it.name }.toList()}",
            1, leftovers.count { it.isFile })
        assertFalse("不该留下临时文件残渣", folder.root.walkTopDown().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun missingFileReadsAsNullAndOccupiedPathFailsLoudly() {
        val absent = File(folder.root, "never-written.txt")
        assertNull(FileSettingsStorage(absent).read())

        val occupied = File(folder.root, "is-a-dir")
        assertTrue(occupied.mkdirs())

        try {
            SettingsStore(FileSettingsStorage(occupied))
            assertTrue("路径被目录占住本该抛异常", false)
        } catch (expected: IOException) {
            assertTrue(
                "报错得带上是哪个路径：${expected.message}",
                expected.message.orEmpty().contains("is-a-dir"),
            )
        }
    }

    @Test(expected = SettingsTooLargeException::class)
    fun oversizeFileIsRefusedInsteadOfLoadedWhole() {
        val file = File(folder.root, "big.txt")
        val store = SettingsStore(FileSettingsStorage(file))
        store.setString("system.prompt", "x".repeat(4096))
        assertTrue(store.save().persisted)

        SettingsStore(FileSettingsStorage(file, maxBytes = 64L))
    }

    companion object {
        /** 取不到值时的哨兵：断言写死它，键丢了就会露出来而不是蒙对默认值。 */
        private const val MISSING = -1
        private const val MISSING_LONG = -1L
    }
}
