package com.hualuo.engine.toolcalls

import com.hualuo.engine.memory.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 记忆工具族对照表：store 不注入=六件都不存在；注入后全链走真逻辑。
 */
class MemoryToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = MemoryStore(
        memoryDir = tmp.newFolder("memory_db"),
        activeFile = tmp.newFile("active_memory.md"),
    )

    @Test
    fun noStoreMeansNoTools() {
        val registry = ToolRegistry()
        MemoryTool.register(registry, store = null)
        assertTrue("不给 store 一件都不注册：", registry.isEmpty())
    }

    @Test
    fun sixToolsRegisteredWithStore() {
        val registry = ToolRegistry()
        MemoryTool.register(registry, store())
        assertEquals(6, registry.size())
        val names = registry.specs().map { it.name }
        assertTrue(names.containsAll(listOf(
            "list_memory_files", "read_memory_file", "create_memory_file",
            "edit_memory_file", "delete_memory_file", "update_active_memory",
        )))
    }

    @Test
    fun createReadListFullChain() {
        val registry = ToolRegistry()
        MemoryTool.register(registry, store())
        val created = registry.execute(
            "create_memory_file",
            """{"name":"偏好.md","content":"用户偏好：直接、聚焦","description":"长期偏好"}""",
        )
        assertTrue("建成功：${created.text}", created.ok)
        val read = registry.execute("read_memory_file", """{"name":"偏好.md"}""")
        assertTrue("原文读回（零脱敏）：${read.text}", read.text.contains("直接、聚焦"))
        val list = registry.execute("list_memory_files", "{}")
        assertTrue("列表带描述：${list.text}", list.text.contains("偏好.md：长期偏好"))
    }

    @Test
    fun patchViaToolRequiresUnique() {
        val registry = ToolRegistry()
        MemoryTool.register(registry, store())
        registry.execute("create_memory_file", """{"name":"a.md","content":"X Y X"}""")
        val out = registry.execute(
            "edit_memory_file",
            """{"name":"a.md","operation":"patch","old_string":"X","new_string":"Z"}""",
        )
        assertFalse("多处命中要拒：${out.text}", out.ok)
        assertTrue(out.text.contains("2 次"))
    }

    @Test
    fun activeMemoryAppendAndPatch() {
        val registry = ToolRegistry()
        MemoryTool.register(registry, store())
        registry.execute("update_active_memory", """{"mode":"append","content":"早睡"}""")
        registry.execute("update_active_memory", """{"mode":"append","content":"少糖"}""")
        val patched = registry.execute(
            "update_active_memory",
            """{"mode":"patch","old_string":"早睡","new_string":"23 点前睡"}""",
        )
        assertTrue(patched.ok)
        // 活动记忆没有读工具（它本来就是喂进上下文的）——直接读文件验
        assertEquals("23 点前睡\n少糖", tmp.root.resolve("active_memory.md").readText())
    }
}
