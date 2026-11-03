package com.hualuo.engine.toolcalls

import com.hualuo.engine.memory.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 技能工具族对照表：store 不注入=五件都不存在；技能库=复用 MemoryStore（skill_db 目录、
 * 无活动记忆文件——写活动记忆必须拒）。catalog 形状对齐旧仓。
 */
class SkillToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): MemoryStore = MemoryStore(tmp.newFolder("skill_db"))

    private fun registry(store: MemoryStore?): ToolRegistry {
        val r = ToolRegistry()
        SkillTool.register(r, store)
        return r
    }

    @Test
    fun noStoreMeansNoTools() {
        assertTrue("不给 store 五件都不注册：", registry(null).isEmpty())
    }

    @Test
    fun fiveToolsRegistered() {
        assertEquals(5, registry(store()).size())
    }

    @Test
    fun skillLibraryHasNoActiveMemory() {
        val s = store()
        assertEquals("没配活动文件读回空：", "", s.readActiveMemory())
        try {
            s.writeActiveMemory("append", "不该能写")
            fail("技能库不许写活动记忆")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("没配活动记忆文件"))
        }
    }

    @Test
    fun createReadEditRoundtrip() {
        val r = registry(store())
        val created = r.execute("create_skill_file", """{"name":"git-rebase","content":"# 交互式变基指南\n正文","description":"怎么安全地 rebase"}""")
        assertTrue(created.ok)
        val read = r.execute("read_skill_file", """{"name":"git-rebase"}""")
        assertTrue("读到全文：${read.text}", read.ok && read.text.contains("交互式变基指南"))
        val patched = r.execute(
            "edit_skill_file",
            """{"name":"git-rebase","operation":"patch","old_string":"指南","new_string":"手册"}""",
        )
        assertTrue(patched.ok)
        val reread = r.execute("read_skill_file", """{"name":"git-rebase"}""")
        assertTrue("改后的正文可见：${reread.text}", reread.ok && reread.text.contains("变基手册"))
    }

    @Test
    fun catalogListsNamesAndDescriptionsOnly() {
        val s = store()
        val out = registry(s).execute(
            "create_skill_file",
            """{"name":"deploy.md","content":"部署正文不该出现在目录里","description":"部署流程"}""",
        )
        assertTrue(out.ok)
        val catalog = SkillTool.catalog(s)
        assertTrue("有名字：$catalog", catalog.contains("deploy.md"))
        assertTrue("有描述：$catalog", catalog.contains("部署流程"))
        assertTrue("目录不进正文：$catalog", !catalog.contains("部署正文"))
        assertTrue("有标签框：$catalog", catalog.contains("<available_skills>"))
    }

    @Test
    fun emptyCatalogIsEmptyString() {
        assertEquals("", SkillTool.catalog(store()))
    }
}
