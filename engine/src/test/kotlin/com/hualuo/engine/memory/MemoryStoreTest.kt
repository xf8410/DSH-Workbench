package com.hualuo.engine.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 记忆库存储件对照表（临时目录，离线）。
 */
class MemoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = MemoryStore(
        memoryDir = tmp.newFolder("memory_db"),
        activeFile = tmp.newFile("active_memory.md"),
    )

    @Test
    fun createListReadRoundtrip() {
        val s = store()
        s.createFile("notes.md", "# 标题\n正文", "我的笔记")
        s.createFile("second", "第二份（不带 .md 后缀）", "")
        val files = s.listFiles()
        assertEquals(2, files.size)
        assertEquals("notes.md", files[0].name)
        assertEquals("我的笔记", files[0].description)
        assertEquals("second.md", files[1].name)
        assertEquals("# 标题\n正文", s.readFile("notes.md"))
    }

    @Test
    fun duplicateNameRejected() {
        val s = store()
        s.createFile("a.md", "x", "")
        try {
            s.createFile("a.md", "y", "")
            fail("重名必须拒")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("已存在"))
        }
    }

    @Test
    fun pathEscapeNeutralized() {
        val s = store()
        s.createFile("../evil", "想逃逸", "")
        // 斜杠被换成下划线：文件落在库内，名字是 .._evil.md
        val names = s.listFiles().map { it.name }
        assertTrue("逃逸被中和：$names", names.contains(".._evil.md"))
        try {
            s.readFile("../../etc/passwd")
            fail("带斜杠的名字不许读出库")
        } catch (e: IllegalArgumentException) {
            // 名字被中和成库内不存在的文件（.._.._etc_passwd.md）——读不到库外任何东西
            assertTrue("拦下且说明去向：${e.message}", e.message!!.contains("不存在"))
        }
    }

    @Test
    fun patchRequiresUniqueMatch() {
        val s = store()
        s.createFile("doc.md", "AAA 中段 BBB", "")
        s.editFile("doc.md", "patch", oldString = "中段", newString = "换成新话")
        assertEquals("AAA 换成新话 BBB", s.readFile("doc.md"))
        try {
            s.editFile("doc.md", "patch", oldString = "库里没有的串", newString = "X")
            fail("零命中必须拒")
        } catch (e: IllegalArgumentException) {
            assertTrue("零命中报错：${e.message}", e.message!!.contains("一次都没命中"))
        }
        s.editFile("doc.md", "replace", content = "CCC AAA DDD AAA")
        try {
            s.editFile("doc.md", "patch", oldString = "AAA", newString = "X")
            fail("多处命中必须拒")
        } catch (e: IllegalArgumentException) {
            assertTrue("多处命中报错：${e.message}", e.message!!.contains("2 次"))
        }
    }

    @Test
    fun renameCarriesDescription() {
        val s = store()
        s.createFile("old.md", "内容", "旧描述")
        s.editFile("old.md", "rename", newName = "new.md")
        val files = s.listFiles()
        assertEquals(1, files.size)
        assertEquals("new.md", files[0].name)
        assertEquals("旧描述", files[0].description)
        assertEquals("内容", s.readFile("new.md"))
    }

    @Test
    fun describeEditsOnlyMeta() {
        val s = store()
        s.createFile("a.md", "内容不动", "旧")
        s.editFile("a.md", "describe", description = "新描述")
        assertEquals("新描述", s.listFiles()[0].description)
        assertEquals("内容不动", s.readFile("a.md"))
    }

    @Test
    fun deleteRemovesFileAndMeta() {
        val s = store()
        s.createFile("a.md", "x", "d")
        s.deleteFile("a.md")
        assertTrue(s.listFiles().isEmpty())
        try {
            s.readFile("a.md")
            fail("删后读必须拒")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("不存在"))
        }
    }

    @Test
    fun activeMemoryFourModes() {
        val s = store()
        assertEquals("", s.readActiveMemory())
        s.writeActiveMemory("append", "第一行")
        s.writeActiveMemory("append", "第二行")
        assertEquals("第一行\n第二行", s.readActiveMemory())
        s.writeActiveMemory("prepend", "顶上")
        assertEquals("顶上\n第一行\n第二行", s.readActiveMemory())
        s.writeActiveMemory("patch", "", oldString = "第一行", newString = "改过的")
        assertEquals("顶上\n改过的\n第二行", s.readActiveMemory())
        s.writeActiveMemory("replace", "整份换掉")
        assertEquals("整份换掉", s.readActiveMemory())
    }

    @Test
    fun brokenMetaFileStartsEmpty() {
        val s = store()
        s.createFile("a.md", "内容", "描述")
        // 蓄意写坏 meta：半个 JSON
        val meta = tmp.root.resolve("memory_db").resolve("memory_meta.json")
        meta.writeText("{half broken")
        // 坏账当空起步：列出来还在（文件在），描述丢了但不出炸
        val files = s.listFiles()
        assertEquals(1, files.size)
        assertEquals("", files[0].description)
    }
}
