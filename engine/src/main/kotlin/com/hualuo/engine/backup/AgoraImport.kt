package com.hualuo.engine.backup

import com.hualuo.engine.store.SessionHead
import com.hualuo.engine.store.StoredMsg
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

// ── 旧 Agora 备份（.agora）格式 ────────────────────────────────────────────
// 按 xf8410/Agora-Workbench 源码钉死（DataExporter.kt / SettingsManager.kt，备份格式版本 1..3）：
// zip 包内五个有用条目，其余（images/、videos/、memories/、custom_font/）当场略过并计数。
// 字段名 = 旧仓 @Serializable 属性名，原样照抄；读不懂的一律按缺省处理，不炸。
//
// **家规（run 35105700713 用两轮红换来的）**：engine 模块刻意不引 kotlin 序列化**编译器插件**
// （engine/build.gradle.kts 里写着缘由）——所以这里严禁 @Serializable + decodeFromString：
// 编译照过、运行时却没有生成的 serializer，runCatching 会把每次 parse 的异常吞成 null，
// 全部字段静默变缺省，测试抓到的只是「recognized=false」这种无头案。
// 本模块解析一律走 Json.parseToJsonElement 动态读，和引擎其他文件同一条路。

/** manifest.json：认包的唯一凭证——没有它或版本不在 1..3，整个包拒收。 */
data class AgoraManifest(
    val agora_export_version: Int = 0,
    val app_version: String = "",
    val exported_at: String = "",
    val categories: List<String> = emptyList(),
    val has_api_keys: Boolean = false,
)

data class AgoraApiKey(
    val id: String = "",
    val name: String = "",
    val key: String = "",
    val provider: String = "",
)

/** api_keys.json：apiKeys 配 activeApiKeyIds（provider 对到 keyId）才能对出「当前用的那把钥匙」。 */
data class AgoraKeys(
    val apiKeys: List<AgoraApiKey> = emptyList(),
    val activeApiKeyIds: Map<String, String> = emptyMap(),
    val webSearchApiKeys: Map<String, String> = emptyMap(),
    val shellApiKeys: Map<String, String> = emptyMap(),
)

data class AgoraCustomProvider(val name: String = "")

/** settings.json 只摘新版能接的字段，其余字段路过不读——贪多必错。 */
data class AgoraSettings(
    val selectedModel: String = "",
    val providerBaseUrls: Map<String, String> = emptyMap(),
    val customProviders: List<AgoraCustomProvider> = emptyList(),
    val thinkingEnabled: Boolean? = null,
    val thinkingLevel: String? = null,
    val codeExecutionEnabled: Boolean? = null,
    val webSearchEnabled: Boolean? = null,
    val shellEnabled: Boolean? = null,
    val activeSystemPromptId: String? = null,
)

data class AgoraPromptItem(
    val id: String = "",
    val type: String = "CUSTOM",
    val value: String = "",
)

data class AgoraPromptEntry(
    val id: String = "",
    val title: String = "",
    val content: String = "",
    val systemItems: List<AgoraPromptItem> = emptyList(),
)

data class AgoraConversation(
    val id: String = "",
    val title: String = "",
    val lastUpdated: Long = 0,
    val modelId: String? = null,
)

/** images/attachmentMeta 用来数「媒体带不过来」的账，不搬运内容。 */
data class AgoraMessage(
    val id: String = "",
    val conversationId: String = "",
    val text: String = "",
    val participant: String = "MODEL",
    val status: String = "SUCCESS",
    val timestamp: Long = 0,
    val images: List<String> = emptyList(),
    val attachmentMeta: String? = null,
)

data class AgoraConversationsBlock(
    val conversations: List<AgoraConversation> = emptyList(),
    val messages: List<AgoraMessage> = emptyList(),
    val tasks: List<JsonObject> = emptyList(),
    val loops: List<JsonObject> = emptyList(),
)

/** 转出来的一个会话：id 已加 agora- 前缀并净化，head/messages 与新版会话库同行同款。 */
data class AgoraSessionPlan(
    val id: String,
    val head: SessionHead,
    val messages: List<StoredMsg>,
)

/**
 * 旧备份的完整兑换单：recognized=false 时其余字段全空、一个字节都不动本地库。
 * 带不过来的东西全在 notes 里说人话，不许静默丢。
 */
data class AgoraImportPlan(
    val recognized: Boolean,
    val formatVersion: Int = 0,
    val providerName: String? = null,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val selectedModel: String? = null,
    val thinkingOn: Boolean? = null,
    val thinkingLevel: Int? = null,
    val codeExecOn: Boolean? = null,
    val webSearchOn: Boolean? = null,
    val shellOn: Boolean? = null,
    val systemPrompt: String? = null,
    val sessions: List<AgoraSessionPlan> = emptyList(),
    val notes: List<String> = emptyList(),
)

/** conversations.json 的字符上限：旧格式是一个大 JSON，超过就明说读不完，不拿内存赌命。 */
const val MAX_AGORA_CONVERSATION_CHARS = 48 * 1024 * 1024

private const val MAX_SMALL_ENTRY_CHARS = 2 * 1024 * 1024

/**
 * 读旧 Agora 备份：单趟 zip 流（不整包进内存），五个条目各有界收进，
 * 逐项兑换成新版的键与会话。任何一步读不懂都落进 notes/recognized=false，绝不抛给界面。
 */
fun readAgoraBackup(input: InputStream): AgoraImportPlan {
    val collected = HashMap<String, String>()
    var ignoredEntries = 0
    ZipInputStream(input.buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name.substringAfterLast('\\', entry.name)
            val cap = when (name) {
                "manifest.json" -> MAX_SMALL_ENTRY_CHARS
                "api_keys.json", "settings.json", "system_prompts.json" -> MAX_SMALL_ENTRY_CHARS
                "conversations.json" -> MAX_AGORA_CONVERSATION_CHARS
                else -> 0
            }
            if (cap == 0 || entry.isDirectory) {
                ignoredEntries++
                zip.closeEntry()
                continue
            }
            collected[name] = readBoundedText(zip, cap)
            zip.closeEntry()
        }
    }

    val manifest = collected["manifest.json"]?.let { parseManifest(it) }
    if (manifest == null || manifest.agora_export_version !in 1..3) {
        return AgoraImportPlan(recognized = false)
    }
    val notes = ArrayList<String>()
    if (manifest.agora_export_version < 3) {
        notes.add("旧备份格式版本 ${manifest.agora_export_version}（v3 之前的草稿字段会有缺，能兑的都兑了）")
    }

    // ── 提供商三件套：activeApiKeyIds 指到哪把钥匙，就兑哪套 ────────────────
    val keys = collected["api_keys.json"]?.let { parseKeys(it) } ?: AgoraKeys()
    val active = keys.activeApiKeyIds.entries.firstOrNull { it.key.isNotBlank() && it.value.isNotBlank() }
    val keyEntry = active?.let { a -> keys.apiKeys.firstOrNull { it.id == a.value } }
    val providerName = keyEntry?.provider?.takeIf { it.isNotBlank() }
        ?: active?.key?.takeIf { it.isNotBlank() }
        ?: keys.apiKeys.firstOrNull()?.provider?.takeIf { it.isNotBlank() }
        ?: collected["settings.json"]?.let { parseSettings(it) }?.customProviders?.firstOrNull()?.name
    val settings = collected["settings.json"]?.let { parseSettings(it) } ?: AgoraSettings()
    val baseUrl = providerName?.let { p -> settings.providerBaseUrls[p]?.takeIf { it.isNotBlank() } }
    val apiKey = keyEntry?.key?.takeIf { it.isNotBlank() }
    if (keys.apiKeys.isNotEmpty() && apiKey == null) {
        notes.add("密钥没兑出来：激活记录对不上钥匙清单（旧包里 key 列表可能是空的）")
    }

    // ── 开关与模型 ─────────────────────────────────────────────────────────
    val thinkingLevel = when (settings.thinkingLevel?.trim()?.lowercase()) {
        "off" -> 0
        "low" -> 1
        "medium", "med" -> 2
        "high" -> 3
        else -> null
    }

    // ── 系统指令：激活的那条；模板变量没法在新版编译，只兑字面部分 ───────────
    val prompts = collected["system_prompts.json"]?.let { parsePrompts(it) } ?: emptyList()
    var systemPrompt: String? = null
    if (settings.activeSystemPromptId != null) {
        val entry = prompts.firstOrNull { it.id == settings.activeSystemPromptId }
        if (entry != null) {
            val text = if (entry.systemItems.isNotEmpty()) {
                entry.systemItems.filter { it.type == "CUSTOM" }.joinToString("") { it.value }
            } else {
                entry.content
            }.trim()
            systemPrompt = text.takeIf { it.isNotEmpty() }
            if (entry.systemItems.any { it.type != "CUSTOM" }) {
                notes.add("系统指令里的模板变量（时间/模型名等）没法原样搬，只兑了固定文字")
            }
        }
    }

    // ── 会话：正文一条不丢，带不过来的逐项数出来 ───────────────────────────
    var conversations: List<AgoraConversation> = emptyList()
    var messages: List<AgoraMessage> = emptyList()
    var tasksCount = 0
    var loopsCount = 0
    var conversationBlockTruncated = false
    collected["conversations.json"]?.let { raw ->
        if (raw.length >= MAX_AGORA_CONVERSATION_CHARS) {
            conversationBlockTruncated = true
        }
        parseConversations(raw)?.let { block ->
            conversations = block.conversations
            messages = block.messages
            tasksCount = block.tasks.size
            loopsCount = block.loops.size
        }
    }
    if (conversationBlockTruncated) {
        notes.add("会话块超过 ${MAX_AGORA_CONVERSATION_CHARS / (1024 * 1024)}MB 没读完：这次只兑设置，会话请分批导或先清旧图再导一次")
    }

    val byConv = LinkedHashMap<String, MutableList<AgoraMessage>>()
    var blankOrMedia = 0
    var unknownParticipant = 0
    var oddStatus = 0
    for (m in messages) {
        val hasMedia = m.images.isNotEmpty() || !m.attachmentMeta.isNullOrBlank()
        if (m.text.isBlank() && hasMedia) {
            blankOrMedia++
            continue
        }
        when (m.participant) {
            "USER", "MODEL" -> byConv.getOrPut(m.conversationId) { ArrayList() }.add(m)
            else -> unknownParticipant++
        }
    }

    val usedIds = HashSet<String>()
    val sessions = ArrayList<AgoraSessionPlan>(conversations.size)
    for (c in conversations) {
        val convMsgs = byConv[c.id] ?: continue
        val mapped = ArrayList<StoredMsg>(convMsgs.size)
        for (m in convMsgs) {
            val role = when {
                m.participant == "USER" -> StoredMsg.ROLE_USER
                m.status == "ERROR" -> StoredMsg.ROLE_ERROR
                else -> StoredMsg.ROLE_ASSISTANT
            }
            if (m.status != "SUCCESS" && m.status != "ERROR") {
                oddStatus++
                continue
            }
            if (m.text.isBlank()) {
                blankOrMedia++
                continue
            }
            mapped.add(StoredMsg(role, m.text, m.timestamp))
        }
        var id = "agora-" + sanitizeId(c.id.ifBlank { "conv" })
        var n = 2
        while (!usedIds.add(id)) {
            id = "agora-" + sanitizeId(c.id.ifBlank { "conv" }) + "-" + n
            n++
        }
        sessions.add(AgoraSessionPlan(id, SessionHead(c.title, c.modelId ?: "", c.lastUpdated), mapped))
    }

    if (blankOrMedia > 0) notes.add("纯附件/空文本消息 ${blankOrMedia} 条没兑（图片视频不在备份里，新版本也不存气泡媒体）")
    if (unknownParticipant > 0) notes.add("身份不明的消息 ${unknownParticipant} 条没兑（旧版工具/系统气泡）")
    if (oddStatus > 0) notes.add("状态异常（非成功也非错误）的消息 ${oddStatus} 条没兑")
    if (tasksCount > 0 || loopsCount > 0) {
        notes.add("定时任务 ${tasksCount} 条、循环 ${loopsCount} 条：新版还没有这功能，没导（已在路线图）")
    }
    if (ignoredEntries > 0) notes.add("包里另有 ${ignoredEntries} 个不认识的条目，原样略过")

    return AgoraImportPlan(
        recognized = true,
        formatVersion = manifest.agora_export_version,
        providerName = providerName,
        baseUrl = baseUrl,
        apiKey = apiKey,
        selectedModel = settings.selectedModel.takeIf { it.isNotBlank() },
        thinkingOn = settings.thinkingEnabled,
        thinkingLevel = thinkingLevel,
        codeExecOn = settings.codeExecutionEnabled,
        webSearchOn = settings.webSearchEnabled,
        shellOn = settings.shellEnabled,
        systemPrompt = systemPrompt,
        sessions = sessions,
        notes = notes,
    )
}

/** 旧 id 只留文件名安全字符，防路径穿越；空了兜底成占位，调用方再撞名去重。 */
private fun sanitizeId(raw: String): String {
    val safe = raw.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
        .joinToString("").take(72)
    return safe.ifEmpty { "conv" }
}

/** 有界读：cap 个字符封顶（UTF-8 由 InputStreamReader 兜住，半个多字节符不会被劈开）。 */
private fun readBoundedText(input: InputStream, cap: Int): String {
    val reader = InputStreamReader(input, StandardCharsets.UTF_8)
    val out = StringBuilder(1 shl 12)
    val buf = CharArray(1 shl 14)
    while (out.length < cap) {
        val n = reader.read(buf, 0, minOf(buf.size, cap - out.length))
        if (n < 0) break
        out.append(buf, 0, n)
    }
    return out.toString()
}

// ── 动态解析（家规路线：parseToJsonElement，坏包一律 null，不许抛出本文件） ────

private fun rootObject(text: String): JsonObject? =
    runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject

private fun strOf(e: JsonElement?): String? = (e as? JsonPrimitive)?.content

private fun longOf(e: JsonElement?): Long? = strOf(e)?.toLongOrNull()

private fun boolOf(e: JsonElement?): Boolean? =
    strOf(e)?.let { it.equals("true", ignoreCase = true) || it == "1" }

private fun strList(e: JsonElement?): List<String> =
    (e as? JsonArray)?.mapNotNull { strOf(it) } ?: emptyList()

/** JsonObject 键值对全按字符串收（旧格式里这些值就是字符串；读不出就丢这对）。 */
private fun stringMap(e: JsonElement?): Map<String, String> =
    (e as? JsonObject)?.entries?.mapNotNull { (k, v) -> strOf(v)?.let { k to it } }?.toMap() ?: emptyMap()

private fun parseManifest(text: String): AgoraManifest? {
    val o = rootObject(text) ?: return null
    return AgoraManifest(
        agora_export_version = longOf(o["agora_export_version"])?.toInt() ?: 0,
        app_version = strOf(o["app_version"]) ?: "",
        exported_at = strOf(o["exported_at"]) ?: "",
        categories = strList(o["categories"]),
        has_api_keys = boolOf(o["has_api_keys"]) ?: false,
    )
}

private fun parseKeys(text: String): AgoraKeys? {
    val o = rootObject(text) ?: return null
    val apiKeys = (o["apiKeys"] as? JsonArray)?.mapNotNull { e ->
        (e as? JsonObject)?.let {
            AgoraApiKey(
                id = strOf(it["id"]) ?: "",
                name = strOf(it["name"]) ?: "",
                key = strOf(it["key"]) ?: "",
                provider = strOf(it["provider"]) ?: "",
            )
        }
    } ?: emptyList()
    return AgoraKeys(
        apiKeys = apiKeys,
        activeApiKeyIds = stringMap(o["activeApiKeyIds"]),
        webSearchApiKeys = stringMap(o["webSearchApiKeys"]),
        shellApiKeys = stringMap(o["shellApiKeys"]),
    )
}

private fun parseSettings(text: String): AgoraSettings? {
    val o = rootObject(text) ?: return null
    val customProviders = (o["customProviders"] as? JsonArray)?.mapNotNull { e ->
        (e as? JsonObject)?.let { AgoraCustomProvider(name = strOf(it["name"]) ?: "") }
    } ?: emptyList()
    return AgoraSettings(
        selectedModel = strOf(o["selectedModel"]) ?: "",
        providerBaseUrls = stringMap(o["providerBaseUrls"]),
        customProviders = customProviders,
        thinkingEnabled = boolOf(o["thinkingEnabled"]),
        thinkingLevel = strOf(o["thinkingLevel"]),
        codeExecutionEnabled = boolOf(o["codeExecutionEnabled"]),
        webSearchEnabled = boolOf(o["webSearchEnabled"]),
        shellEnabled = boolOf(o["shellEnabled"]),
        activeSystemPromptId = strOf(o["activeSystemPromptId"]),
    )
}

private fun parsePrompts(text: String): List<AgoraPromptEntry> {
    val arr = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
    return arr.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val items = (o["systemItems"] as? JsonArray)?.mapNotNull { i ->
            (i as? JsonObject)?.let {
                AgoraPromptItem(
                    id = strOf(it["id"]) ?: "",
                    type = strOf(it["type"]) ?: "CUSTOM",
                    value = strOf(it["value"]) ?: "",
                )
            }
        } ?: emptyList()
        AgoraPromptEntry(
            id = strOf(o["id"]) ?: "",
            title = strOf(o["title"]) ?: "",
            content = strOf(o["content"]) ?: "",
            systemItems = items,
        )
    }
}

private fun parseConversations(text: String): AgoraConversationsBlock? {
    val o = rootObject(text) ?: return null
    val conversations = (o["conversations"] as? JsonArray)?.mapNotNull { e ->
        (e as? JsonObject)?.let {
            AgoraConversation(
                id = strOf(it["id"]) ?: "",
                title = strOf(it["title"]) ?: "",
                lastUpdated = longOf(it["lastUpdated"]) ?: 0,
                modelId = strOf(it["modelId"]),
            )
        }
    } ?: emptyList()
    val messages = (o["messages"] as? JsonArray)?.mapNotNull { e ->
        (e as? JsonObject)?.let {
            AgoraMessage(
                id = strOf(it["id"]) ?: "",
                conversationId = strOf(it["conversationId"]) ?: "",
                text = strOf(it["text"]) ?: "",
                participant = strOf(it["participant"]) ?: "MODEL",
                status = strOf(it["status"]) ?: "SUCCESS",
                timestamp = longOf(it["timestamp"]) ?: 0,
                images = strList(it["images"]),
                attachmentMeta = strOf(it["attachmentMeta"]),
            )
        }
    } ?: emptyList()
    return AgoraConversationsBlock(
        conversations = conversations,
        messages = messages,
        tasks = (o["tasks"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList(),
        loops = (o["loops"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList(),
    )
}
