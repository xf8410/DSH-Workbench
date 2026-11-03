package com.hualuo.engine.toolcalls

import com.hualuo.engine.automation.TaskStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 任务工具族对照表：store 不注入=五件都不存在；创建/列表/启停/删除走真逻辑（离线）。
 */
class TaskToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): TaskStore = TaskStore(File(tmp.root, "tasks.jsonl"))

    private fun registry(store: TaskStore?): ToolRegistry {
        val r = ToolRegistry()
        TaskTool.register(r, store)
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
    fun createThenListShowsIt() {
        val r = registry(store())
        val created = r.execute("create_task", """{"name":"晨报","prompt":"跑晨报","cron":"0 9 * * *"}""")
        assertTrue("建任务成功：${created.text}", created.ok && created.text.contains("已建任务"))
        val listed = r.execute("list_tasks", "{}")
        assertTrue("列表见到晨报：${listed.text}", listed.ok && listed.text.contains("晨报") && listed.text.contains("[启用]"))
    }

    @Test
    fun badCronRejectedAtCreate() {
        val out = registry(store()).execute("create_task", """{"name":"坏","prompt":"x","cron":"0 25 * * *"}""")
        assertTrue("小时越界当场拒：${out.text}", !out.ok && out.text.contains("cron 不合法"))
    }

    @Test
    fun disableEnableDeleteRoundtrip() {
        val r = registry(store())
        val created = r.execute("create_task", """{"name":"周期","prompt":"跑","cron":"0 9 * * *"}""")
        assertTrue(created.ok)
        val off = r.execute("disable_task", """{"id_or_name":"周期"}""")
        assertTrue("停用按名：${off.text}", off.ok)
        val on = r.execute("enable_task", """{"id_or_name":"周期"}""")
        assertTrue("再启用：${on.text}", on.ok)
        val gone = r.execute("delete_task", """{"id_or_name":"周期"}""")
        assertTrue("删除按名：${gone.text}", gone.ok && gone.text.contains("已删任务"))
        val empty = r.execute("list_tasks", "{}")
        assertTrue("删完空账：${empty.text}", empty.ok && empty.text.contains("一条任务都没有"))
    }

    @Test
    fun missingTaskFailsCleanly() {
        val out = registry(store()).execute("delete_task", """{"id_or_name":"ghost"}""")
        assertTrue("没这条要拒：${out.text}", !out.ok && out.text.contains("没有这条任务"))
    }
}
