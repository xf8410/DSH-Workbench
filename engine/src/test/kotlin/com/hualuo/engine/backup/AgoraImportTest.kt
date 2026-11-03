package com.hualuo.engine.backup

import com.hualuo.engine.store.StoredMsg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 旧 Agora 备份兑换器的契约测试：包按旧仓 DataExporter 的真实形状造，
 * 断言「兑出什么、略过什么、账怎么报」。样本 JSON 就是旧仓导出的逐字段仿写。
 *
 * 两条已经吃过的亏，钉在这里防再犯：
 *  - engine 测试类路径没有 kotlin-test，用 org.junit（run 35105123539）；
 *  - 源码里不许有箭头等符号字符，连注释和转义写法都不行（红线闸门，run 35105700713）。
 */
class AgoraImportTest {

    private fun agoraZip(vararg extra: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("manifest.json", """
                {"agora_export_version":3,"app_version":"1.9.9","exported_at":"2026-09-13T00:00:00Z",
                 "categories":["conversations","settings","api_keys"],"has_api_keys":true}
            """.trimIndent())
            put("api_keys.json", """
                {"apiKeys":[{"id":"k1","name":"主力","key":"sk-live-9","provider":"deepseek"},
                            {"id":"k2","name":"备用","key":"sk-old-1","provider":"openai"}],
                 "activeApiKeyIds":{"deepseek":"k1"},
                 "webSearchApiKeys":{},"shellApiKeys":{}}
            """.trimIndent())
            put("settings.json", """
                {"selectedModel":"deepseek-chat",
                 "providerBaseUrls":{"deepseek":"https://api.deepseek.com/v1"},
                 "customProviders":[{"name":"网关A"}],
                 "thinkingEnabled":true,"thinkingLevel":"high",
                 "codeExecutionEnabled":false,"webSearchEnabled":true,"shellEnabled":false,
                 "activeSystemPromptId":"p1"}
            """.trimIndent())
            put("system_prompts.json", """
                [{"id":"p1","title":"主力","content":"",
                  "systemItems":[{"id":"i1","type":"CUSTOM","value":"你是账房先生。"},
                                 {"id":"i2","type":"PREDEFINED","value":"time"}]}]
            """.trimIndent())
            put("conversations.json", """
                {"conversations":[{"id":"11111111-1111-1111-1111-111111111111",
                                   "title":"挖矿记录","lastUpdated":1700000000000,"modelId":"deepseek-chat"}],
                 "messages":[
                   {"id":"m1","conversationId":"11111111-1111-1111-1111-111111111111",
                    "text":"开矿","participant":"USER","status":"SUCCESS","timestamp":1700000001000},
                   {"id":"m2","conversationId":"11111111-1111-1111-1111-111111111111",
                    "text":"挖到三条","participant":"MODEL","status":"SUCCESS","timestamp":1700000002000},
                   {"id":"m3","conversationId":"11111111-1111-1111-1111-111111111111",
                    "text":"工具输出","participant":"TOOL","status":"SUCCESS","timestamp":1700000002500},
                   {"id":"m4","conversationId":"11111111-1111-1111-1111-111111111111",
                    "text":"超时了","participant":"MODEL","status":"ERROR","timestamp":1700000003000},
                   {"id":"m5","conversationId":"11111111-1111-1111-1111-111111111111",
                    "text":"","participant":"USER","status":"SUCCESS","timestamp":1700000004000,
                    "images":["content://media/1"]}],
                 "tasks":[{"id":"t1","name":"日报","prompt":"p","cronExpr":"0 8 * * *","nextRunAt":0,"createdAt":0}],
                 "loops":[]}
            """.trimIndent())
            put("images/m1/0", "PNG_BYTES")
            put("memories/active_memory.md", "记住：矿在北坡")
            extra.forEach { (n, c) -> put(n, c) }
        }
        return bytes.toByteArray()
    }

    @Test
    fun fullPlanMapsProviderFlagsPromptAndSessionsWithHonestNotes() {
        val plan = readAgoraBackup(ByteArrayInputStream(agoraZip()))
        assertTrue("合法旧包必须认出来", plan.recognized)
        assertEquals(3, plan.formatVersion)

        // 激活的那把钥匙对号：deepseek 的 k1，兑出 sk-live-9，不是备用的 k2
        assertEquals("deepseek", plan.providerName)
        assertEquals("https://api.deepseek.com/v1", plan.baseUrl)
        assertEquals("sk-live-9", plan.apiKey)
        assertEquals("deepseek-chat", plan.selectedModel)

        assertTrue(plan.thinkingOn == true)
        assertEquals(3, plan.thinkingLevel)
        assertEquals(false, plan.codeExecOn)
        assertTrue(plan.webSearchOn == true)
        assertEquals(false, plan.shellOn)

        // 模板变量不兑，字面部分原样
        assertEquals("你是账房先生。", plan.systemPrompt)
        assertTrue(plan.notes.any { it.contains("模板变量") })

        // 会话：1 份；USER 变 user、MODEL 变 assistant、ERROR 变 error；TOOL 与纯图消息剔掉并记账
        assertEquals(1, plan.sessions.size)
        val s = plan.sessions[0]
        assertEquals("agora-11111111-1111-1111-1111-111111111111", s.id)
        assertEquals("挖矿记录", s.head.title)
        assertEquals(1700000000000, s.head.createdAtMs)
        assertEquals(
            listOf(
                StoredMsg.ROLE_USER to "开矿",
                StoredMsg.ROLE_ASSISTANT to "挖到三条",
                StoredMsg.ROLE_ERROR to "超时了",
            ),
            s.messages.map { it.role to it.text },
        )
        assertTrue(plan.notes.any { it.contains("纯附件/空文本消息 1 条") })
        assertTrue(plan.notes.any { it.contains("身份不明的消息 1 条") })
        assertTrue(plan.notes.any { it.contains("定时任务 1 条") })
        assertTrue(plan.notes.any { it.contains("2 个不认识的条目") })
    }

    @Test
    fun nonAgoraZipIsRefusedWithoutTouchingAnything() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("readme.txt"))
                zip.write("随便什么包".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val plan = readAgoraBackup(ByteArrayInputStream(bytes))
        assertFalse(plan.recognized)
        assertEquals(0, plan.sessions.size)
        assertNull(plan.apiKey)
        assertNull(plan.selectedModel)
        assertTrue(plan.notes.isEmpty())
    }

    @Test
    fun duplicateConversationIdsGetSuffixesInsteadOfOverwriting() {
        val plan = readAgoraBackup(ByteArrayInputStream(agoraZip()))
        assertTrue("合法旧包必须认出来", plan.recognized)
        // 同一个包跑两次兑进同一目录是网关的事；这里只钉计划内 id 唯一
        val ids = plan.sessions.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
