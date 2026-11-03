package com.hualuo.engine.toolcalls

import com.hualuo.engine.store.SessionStore
import com.hualuo.engine.store.SessionHead
import com.hualuo.engine.store.StoredMsg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 对话检索工具族对照表：store 不注入=三件都不存在；搜索/窗口合并/分页走真逻辑（离线）。
 */
class RagToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): SessionStore = SessionStore(tmp.newFolder())

    private fun registry(store: SessionStore?): ToolRegistry {
        val r = ToolRegistry()
        RagTool.register(r, store)
        return r
    }

    private var seedClock = 1_000L

    private fun seedConversation(store: SessionStore, title: String, vararg texts: Pair<String, String>): String {
        val id = store.create("test-model")
        store.rename(id, title)
        val base = ++seedClock * 60_000L
        texts.forEachIndexed { i, (role, text) ->
            store.append(id, StoredMsg(role, text, atMs = base + i * 1_000L))
        }
        return id
    }

    @Test
    fun noStoreMeansNoTools() {
        assertTrue("不给 store 三件都不注册：", registry(null).isEmpty())
    }

    @Test
    fun threeToolsRegisteredWithStore() {
        val names = registry(store()).specs().map { it.name }
        assertEquals(listOf("search_conversations", "list_conversations", "read_conversation"), names)
    }

    @Test
    fun searchFindsAndWindowsAcrossMessages() {
        val s = store()
        val id = seedConversation(
            s, "拉面杯计划",
            "user" to "第一句闲话",
            "user" to "我们决定用 MCTS 决策",
            "assistant" to "好，采样策略记下了",
            "user" to "再一句闲话",
            "user" to "MCTS 的预算怎么分",
            "assistant" to "按轮次均分就行",
        )
        val out = registry(s).execute("search_conversations", """{"query":"MCTS"}""")
        assertTrue("命中：${out.text}", out.ok)
        assertTrue("找到会话：${out.text}", out.text.contains(id))
        assertTrue("带窗口标题：${out.text}", out.text.contains("拉面杯计划"))
        // 两条命中消息隔着 1 条闲话——窗口(±3)应当合并成一个
        val count = Regex("\"match_count\":(\\d+)").find(out.text)!!.groupValues[1].toInt()
        assertEquals("两条 MCTS 命中都算：", 2, count)
        assertFalse("错误卡不进搜索结果", out.text.contains("error 卡"))
    }

    @Test
    fun searchAndSemanticsRequiresAllTerms() {
        val s = store()
        seedConversation(s, "只有一半词", "user" to "MCTS 很厉害")
        seedConversation(s, "两个词都有", "user" to "MCTS 配上 拉面 才完整")
        val out = registry(s).execute("search_conversations", """{"query":"MCTS 拉面"}""")
        assertTrue(out.ok)
        assertFalse("只含一半词的会话不算命中：${out.text}", out.text.contains("只有一半词"))
        assertTrue(out.text.contains("两个词都有"))
    }

    @Test
    fun searchNoResultsReportsScanned() {
        val s = store()
        seedConversation(s, "空盒子", "user" to "无关内容", "assistant" to "嗯")
        val out = registry(s).execute("search_conversations", """{"query":"不存在的词"}""")
        assertTrue(out.ok)
        assertTrue("no_results + 扫描账：${out.text}", out.text.contains("no_results") && out.text.contains("scanned_messages"))
    }

    @Test
    fun listOrdersAndPaginates() {
        val s = store()
        seedConversation(s, "老会话", "user" to "老")
        seedConversation(s, "新会话", "user" to "新")
        val out = registry(s).execute("list_conversations", """{"order":"desc","limit":1,"offset":0}""")
        assertTrue(out.ok)
        assertTrue("新在前：${out.text}", out.text.contains("新会话"))
        assertFalse("分页只拿一条：${out.text}", out.text.contains("老会话"))
        val page2 = registry(s).execute("list_conversations", """{"order":"desc","limit":1,"offset":1}""")
        assertTrue("第二页是老会话：${page2.text}", page2.text.contains("老会话"))
    }

    @Test
    fun readConversationPaginatesAndReportsTotal() {
        val s = store()
        val id = seedConversation(
            s, "五条",
            "user" to "一", "assistant" to "二", "user" to "三", "assistant" to "四", "user" to "五",
        )
        val out = registry(s).execute("read_conversation", """{"conversation_id":"$id","offset":1,"limit":2}""")
        assertTrue(out.ok)
        assertTrue("总数在账：${out.text}", out.text.contains("\"total_messages\":5"))
        assertTrue("第二页起头是二：${out.text}", out.text.contains("\"text\":\"二\""))
        assertFalse("不该有一：${out.text}", out.text.contains("\"text\":\"一\""))
    }

    @Test
    fun readMissingConversationFailsCleanly() {
        val out = registry(store()).execute("read_conversation", """{"conversation_id":"ghost"}""")
        assertTrue(out.ok) // 工具执行成功，结果是「没找到」的事实
        assertTrue("not_found：${out.text}", out.text.contains("not_found"))
    }
}
