package com.hualuo.engine.toolcalls

import com.hualuo.engine.store.SessionStore
import com.hualuo.engine.store.StoredMsg
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对话检索工具族（M4 第三刀，语义对齐旧 Agora RagToolProvider 的三件：
 * search / list / read past conversations）。
 *
 * 新仓数据模型差异（实读两边源码后的对齐决策）：
 *  - 新仓会话无分支树（旧仓 buildSelectedTopologyBranch 那套不存在）——线性消息即全量；
 *  - 新仓还没接 embedding 基础设施——本刀只做**关键词搜索**（旧仓
 *    ctx.modelSearchMethod != RAG 时的同款路径），语义搜索等 embedding 进新仓再补刀；
 *  - 搜索窗口化语义保留：命中消息前后各 [HALF_WINDOW] 条合成窗口，重叠窗口合并，
 *    单窗封顶 [MAX_WINDOW]，总消息量封顶 [TOTAL_CAP]——不许一次检索把上下文撑爆。
 *
 * **给 [store] 才存在**：不注入 SessionStore 一件都不注册（闸门纪律同写类/PR/记忆族）。
 * 零脱敏：消息原文进出，不做任何过滤。
 */
object RagTool {

    private const val HALF_WINDOW = 3
    private const val MAX_WINDOW = 8
    private const val TOTAL_CAP = 200
    private val TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun register(registry: ToolRegistry, store: SessionStore?) {
        if (store == null) return

        registry.register(
            ToolSpec(
                name = "search_conversations",
                description = "搜过去的对话找相关信息（关键词，全部词都命中才算）。用来回忆以前聊过的事实、决定与背景。",
                parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"搜索词（按空白分词，AND 语义）"},"limit":{"type":"integer","description":"最多返回几个会话窗口（1-20，默认 10）"}},"required":["query"]}""",
            )
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val query = reqStr(args, "query", "搜索词")
            val limit = optInt(args, "limit", 10).coerceIn(1, 20)
            search(store, query, limit)
        }

        registry.register(
            ToolSpec(
                name = "list_conversations",
                description = "列出全部过去的对话（会话 ID、标题、模型、最后更新时间），配合 read_conversation 翻内容。",
                parametersJson = """{"type":"object","properties":{"order":{"type":"string","description":"按更新时间排：desc 新在前（默认）/ asc 旧在前"},"limit":{"type":"integer","description":"每页条数（1-50，默认 20）"},"offset":{"type":"integer","description":"跳过条数（分页，默认 0）"}},"required":[]}""",
            )
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val asc = (args["order"] as? JsonPrimitive)?.contentOrNull == "asc"
            val limit = optInt(args, "limit", 20).coerceIn(1, 50)
            val offset = optInt(args, "offset", 0).coerceAtLeast(0)
            listConversations(store, asc, limit, offset)
        }

        registry.register(
            ToolSpec(
                name = "read_conversation",
                description = "按会话 ID 读一条过去的对话（分页线性消息：谁说的、原文、时间）。先 list_conversations 或 search_conversations 拿到 ID 再读。",
                parametersJson = """{"type":"object","properties":{"conversation_id":{"type":"string","description":"会话 ID（list/search 结果里有）"},"offset":{"type":"integer","description":"跳过消息数（默认 0）"},"limit":{"type":"integer","description":"每页消息数（1-100，默认 50）"}},"required":["conversation_id"]}""",
            )
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val id = reqStr(args, "conversation_id", "会话 ID")
            val offset = optInt(args, "offset", 0).coerceAtLeast(0)
            val limit = optInt(args, "limit", 50).coerceIn(1, 100)
            readConversation(store, id, offset, limit)
        }
    }

    // ---------- 搜索 ----------

    /** 一个命中窗口：会话 + 消息段 + 命中账。 */
    private data class Window(
        val convId: String,
        val title: String,
        val msgs: List<StoredMsg>,
        val hitCount: Int,
    )

    private fun search(store: SessionStore, query: String, limit: Int): String {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return json("""{"type":"search_conversations","query":%Q,"error":"no_query"}""", query, 0)

        val listing = store.list()
        val windows = mutableListOf<Window>()
        var scanned = 0

        for ((convId, head) in listing.heads) {
            val loaded = store.load(convId) ?: continue
            val feedable = loaded.messages.filter { it.role != StoredMsg.ROLE_ERROR && it.text.isNotEmpty() }
            scanned += feedable.size
            val hitIdx = feedable.indices.filter { i -> terms.all { feedable[i].text.lowercase().contains(it) } }
            if (hitIdx.isEmpty()) continue

            // 命中 ±HALF_WINDOW 开窗，重叠（或贴着）合并
            val rawRanges = hitIdx.map { i ->
                val before = HALF_WINDOW.coerceAtMost(i)
                val after = HALF_WINDOW.coerceAtMost(feedable.size - 1 - i)
                (i - before)..(i + after)
            }
            val merged = mutableListOf<IntRange>()
            for (r in rawRanges.sortedBy { it.first }) {
                val last = merged.lastOrNull()
                if (last != null && r.first <= last.last + 1) {
                    merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
                } else merged.add(r)
            }
            for (r in merged) {
                val capped = capWindow(r, hitIdx)
                val slice = feedable.subList(capped.first, capped.last + 1)
                windows.add(Window(convId, head.title, slice, hitIdx.count { it in capped }))
            }
        }

        if (windows.isEmpty()) {
            return json("""{"type":"search_conversations","query":%Q,"error":"no_results","scanned_messages":%N}""", query, scanned)
        }

        // 按（窗口命中数，会话更新时间）排序，总消息量封顶
        val lastAt = listing.heads.associate { (id, _) -> id to lastActivityMs(store, id) }
        val ordered = windows.sortedWith(
            compareByDescending<Window> { it.hitCount }.thenByDescending { lastAt[it.convId] ?: 0L }
        )
        val sb = StringBuilder("{\"type\":\"search_conversations\",\"query\":")
        sb.append(jsonStr(query)).append(",\"count\":")
        var total = 0
        val kept = mutableListOf<Window>()
        for (w in ordered) {
            if (total >= TOTAL_CAP || kept.size >= limit) break
            val available = TOTAL_CAP - total
            kept.add(if (w.msgs.size > available) Window(w.convId, w.title, w.msgs.take(available), w.hitCount) else w)
            total += kept.last().msgs.size
        }
        sb.append(kept.size).append(",\"results\":[")
        kept.forEachIndexed { i, w ->
            if (i > 0) sb.append(',')
            sb.append("{\"title\":").append(jsonStr(w.title.ifBlank { "(无标题)" }))
            sb.append(",\"conversation_id\":").append(jsonStr(w.convId))
            sb.append(",\"match_count\":").append(w.hitCount)
            sb.append(",\"messages\":[")
            w.msgs.forEachIndexed { j, m ->
                if (j > 0) sb.append(',')
                sb.append("{\"participant\":").append(jsonStr(m.role))
                    .append(",\"text\":").append(jsonStr(m.text))
                    .append(",\"timestamp\":\"").append(TS.format(Date(m.atMs))).append("\"}")
            }
            sb.append("]}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /** 单窗超 MAX_WINDOW 就以最高命中点为中心截。 */
    private fun capWindow(range: IntRange, hits: List<Int>): IntRange {
        if (range.last - range.first + 1 <= MAX_WINDOW) return range
        val center = hits.firstOrNull { it in range } ?: ((range.first + range.last) / 2)
        val start = (center - MAX_WINDOW / 2).coerceAtLeast(range.first)
        val end = (start + MAX_WINDOW - 1).coerceAtMost(range.last)
        return start..end
    }

    // ---------- 列表 ----------

    private fun listConversations(store: SessionStore, asc: Boolean, limit: Int, offset: Int): String {
        val listing = store.list()
        val rows = listing.heads.map { (id, head) ->
            Triple(id, head, lastActivityMs(store, id))
        }.sortedBy { it.third }.let { if (asc) it else it.asReversed() }
        val page = rows.drop(offset).take(limit)
        val sb = StringBuilder("{\"type\":\"list_conversations\",\"count\":")
        sb.append(page.size).append(",\"total\":").append(rows.size)
        if (listing.unreadable > 0) sb.append(",\"unreadable\":").append(listing.unreadable)
        sb.append(",\"conversations\":[")
        page.forEachIndexed { i, (id, head, lastMs) ->
            if (i > 0) sb.append(',')
            sb.append("{\"conversation_id\":").append(jsonStr(id))
                .append(",\"title\":").append(jsonStr(head.title.ifBlank { "(无标题)" }))
                .append(",\"model\":").append(jsonStr(head.model))
                .append(",\"last_updated\":\"").append(TS.format(Date(lastMs))).append("\"}")
        }
        sb.append("]}")
        return sb.toString()
    }

    // ---------- 读取 ----------

    private fun readConversation(store: SessionStore, id: String, offset: Int, limit: Int): String {
        val loaded = store.load(id)
            ?: return json("""{"type":"read_conversation","conversation_id":%Q,"error":"not_found"}""", id, 0)
        val page = loaded.messages.drop(offset).take(limit)
        val sb = StringBuilder("{\"type\":\"read_conversation\",\"conversation_id\":")
        sb.append(jsonStr(id)).append(",\"title\":")
        sb.append(jsonStr((loaded.head?.title ?: "").ifBlank { "(无标题)" }))
        sb.append(",\"total_messages\":").append(loaded.messages.size)
        sb.append(",\"offset\":").append(offset).append(",\"count\":").append(page.size)
        if (loaded.badLines > 0) sb.append(",\"bad_lines\":").append(loaded.badLines)
        sb.append(",\"messages\":[")
        page.forEachIndexed { i, m ->
            if (i > 0) sb.append(',')
            sb.append("{\"participant\":").append(jsonStr(m.role))
                .append(",\"text\":").append(jsonStr(m.text))
                .append(",\"timestamp\":\"").append(TS.format(Date(m.atMs))).append("\"")
            if (m.incomplete) sb.append(",\"incomplete\":true")
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun lastActivityMs(store: SessionStore, id: String): Long =
        store.load(id)?.messages?.lastOrNull()?.atMs
            ?: store.list().heads.firstOrNull { it.first == id }?.second?.createdAtMs
            ?: 0L

    // ---------- 内部件 ----------

    private fun argsOf(argumentsJson: String): JsonObject =
        runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: JsonObject(emptyMap())

    private fun reqStr(args: JsonObject, key: String, what: String): String =
        (args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("缺参数 $key（$what）")

    private fun optInt(args: JsonObject, key: String, default: Int): Int =
        (args[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: default

    /** JSON 字符串字面量（含引号）。 */
    private fun jsonStr(s: String): String = JsonPrimitive(s).toString()

    /** 小模板：%Q=字符串参数，%N=整数参数。 */
    private fun json(template: String, s: String, n: Int): String =
        template.replace("%Q", jsonStr(s)).replace("%N", n.toString())
}
