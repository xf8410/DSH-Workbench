package com.hualuo.engine.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SessionStore 契约测试（全在 java.io 上跑，纯 JVM，不碰 Android）。
 *
 * 钉的是「盘上不许撒谎」这一族规矩：写出去的字原样读回来（含换行引号）、
 * 错误卡照存但永远不回喂、裁剪和坏行都要数着报、追加撞坏文件不许吞前文。
 *
 * 教训三课（CI 编译/测试段抓的）：①pathOf 返回的就是 File，别再套 File(...)；
 * ②③create 自己生成 id（参数是模型名）——想验净化规矩，拿 pathOf 直接问落点、
 * 把花样 id 递进 append/load 这些真的收 id 的口，别指望 create 收下它。
 */
class SessionStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = SessionStore(tmp.root)

    private fun msg(role: String, text: String, incomplete: Boolean = false) =
        StoredMsg(role, text, 1000L, incomplete)

    @Test
    fun roundTripKeepsEveryCharacter() {
        val s = store()
        val id = s.create("qwen3.8-flash")
        s.append(id, StoredMsg(StoredMsg.ROLE_USER, "多行\n带\"引号\"、反斜杠\\、制表\t、中文、尖括号<>与&符号", 1L))
        s.append(id, StoredMsg(StoredMsg.ROLE_ASSISTANT, "行尾空格   ", 2L))

        val loaded = s.load(id)!!
        assertEquals("新会话没标题就是空，不许编一个装样子", "", loaded.head!!.title)
        assertEquals("qwen3.8-flash", loaded.head!!.model)
        assertEquals(0, loaded.badLines)
        assertEquals(2, loaded.messages.size)
        assertEquals("多行\n带\"引号\"、反斜杠\\、制表\t、中文、尖括号<>与&符号", loaded.messages[0].text)
        assertEquals("行尾空格   ", loaded.messages[1].text)
        assertEquals(StoredMsg.ROLE_USER, loaded.messages[0].role)
    }

    @Test
    fun incompleteFlagSurvivesReload() {
        val s = store()
        val id = s.create("m")
        s.append(id, msg(StoredMsg.ROLE_ASSISTANT, "说到一半断", incomplete = true))

        val back = s.load(id)!!.messages.single()
        assertTrue("半截标记丢了就等于重载后拿断话冒充成品", back.incomplete)
    }

    @Test
    fun errorCardsStoredButNeverFed() {
        val s = store()
        val id = s.create("m")
        s.append(id, msg(StoredMsg.ROLE_USER, "第一问"))
        s.append(id, msg(StoredMsg.ROLE_ERROR, "对方服务出错（500）：等一会儿再发"))
        s.append(id, msg(StoredMsg.ROLE_USER, "第二问"))
        s.append(id, msg(StoredMsg.ROLE_ASSISTANT, "答"))

        val loaded = s.load(id)!!
        assertEquals("错误卡照实存盘（重载后界面还得摆回去）", 4, loaded.messages.size)

        val feed = s.feedFor(id, maxTurns = 10)
        assertEquals("喂模型时错误卡必须被剔掉", 3, feed.feed.size)
        assertFalse(feed.feed.any { it.first == StoredMsg.ROLE_ERROR })
        assertEquals("剔了几条要数着报", 1, feed.droppedNonFeedable)
    }

    @Test
    fun feedTrimsFromHeadAndReportsCount() {
        val s = store()
        val id = s.create("m")
        for (i in 1..5) s.append(id, msg(StoredMsg.ROLE_USER, "话$i"))

        val feed = s.feedFor(id, maxTurns = 3)
        assertEquals("掐头留尾：留下的必须是最贴近现在的三条、顺序不变",
            listOf("user" to "话3", "user" to "话4", "user" to "话5"), feed.feed)
        assertEquals(2, feed.trimmedCount)
        assertEquals("护栏收成 0 也不许越界", 0, s.feedFor(id, maxTurns = 0).feed.size)
    }

    @Test
    fun badLinesAreCountedNotSwallowed() {
        val s = store()
        val id = s.create("m")
        s.append(id, msg(StoredMsg.ROLE_USER, "好行一"))
        // 手动塞两种坏行：缺右花括号的残行、角色不认识的行
        s.pathOf(id).appendText("{k:m,role:user\n")
        s.append(id, msg(StoredMsg.ROLE_ASSISTANT, "好行二"))
        s.pathOf(id).appendText("{\"k\":\"m\",\"role\":\"unknown-role\",\"text\":\"\",\"at\":1}\n")

        val loaded = s.load(id)!!
        assertEquals("两条好行原样在", listOf("好行一", "好行二"), loaded.messages.map { it.text })
        assertEquals("坏了几行就得报几行", 2, loaded.badLines)
    }

    @Test
    fun missingHeadStillLoadsMessagesAndSaysSo() {
        val s = store()
        val id = s.create("m")
        s.append(id, msg(StoredMsg.ROLE_USER, "没头也行"))
        val f = s.pathOf(id)
        // 把头行删掉：整段内容不许因为头烂了就全不见——首行按消息再解一次
        f.writeText(f.readLines().drop(1).joinToString("\n", postfix = "\n"))

        val loaded = s.load(id)!!
        assertNull("头读不懂就明说读不懂", loaded.head)
        assertEquals("首行落按消息解：这条不许被冤枉成坏行", 0, loaded.badLines)
        assertEquals("消息原样在", listOf("没头也行"), loaded.messages.map { it.text })
    }

    @Test
    fun createIdsNeverCollideEvenSameMillisecond() {
        val s = store()
        val ids = List(50) { s.create("m") }.toSet()
        assertEquals("同一毫秒连开 50 个会话也不许撞名", 50, ids.size)
    }

    @Test
    fun renameRewritesHeadAndKeepsBody() {
        val s = store()
        val id = s.create("m")
        s.append(id, msg(StoredMsg.ROLE_USER, "聊聊重构"))
        assertTrue(s.rename(id, "聊聊重构"))

        val loaded = s.load(id)!!
        assertEquals("聊聊重构", loaded.head!!.title)
        assertEquals("m", loaded.head!!.model)
        assertEquals("改头不许伤正文", listOf("聊聊重构"), loaded.messages.map { it.text })
        assertEquals("临时文件不许留在盘上", 0, tmp.root.listFiles { f -> f.name.endsWith(".tmp") }!!.size)
    }

    @Test
    fun listSortsNewestFirstAndCountsCorruptFiles() {
        val s = store()
        val old = s.create("m")
        Thread.sleep(2) // createdAtMs 毫秒级，连开两条得拉开一瞬才分得出先后
        val fresh = s.create("m")
        s.pathOf("corrupt").writeText("这不是 jsonl\n")

        val listing = s.list()
        assertEquals(listOf(fresh, old), listing.heads.map { it.first })
        assertEquals("读不出头的文件要数出来，不许装作不存在", 1, listing.unreadable)
    }

    @Test
    fun appendToUnknownSessionFailsLoudlyNotSilently() {
        val s = store()
        assertFalse("给不存在的会话追加必须返回 false，让调用方出声", s.append("s未知", msg(StoredMsg.ROLE_USER, "hi")))
        assertNull(s.load("s未知"))
    }

    @Test
    fun weirdIdsCannotEscapeDirectory() {
        val s = store()
        // 路径穿越的 id 先问落点：净化后必须仍躺在仓目录内
        val landed = s.pathOf("../../etc/evil")
        assertEquals("花样 id 的落点不许出仓目录", tmp.root, landed.parentFile)
        assertFalse("落点名里不许再有目录分隔：${landed.name}", landed.name.contains('/'))

        // 再把花样 id 递进真的收 id 的口：没有这条会话就如实扑空，且不许写出任何新文件
        assertFalse(s.append("../../etc/evil", msg(StoredMsg.ROLE_USER, "想黑谁")))
        assertNull(s.load("../../etc/evil"))
        assertTrue("扑空的操作不许顺手造文件", tmp.root.listFiles { f -> f.isFile }!!.isEmpty())
    }

    @Test
    fun deleteRemovesTheWholeSession() {
        val s = store()
        val id = s.create("m")
        s.append(id, msg(StoredMsg.ROLE_USER, "说过话"))
        assertTrue(s.delete(id))
        assertFalse(s.exists(id))
        assertFalse("再删一次如实说没删到", s.delete(id))
    }
}
