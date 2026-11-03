package com.hualuo.engine.store

import java.io.File

/**
 * 会话头（jsonl 首行）：标题、用的模型、创建时刻。
 * title 允许空——新建会话还没说过话时就是没标题，不许编一个装样子。
 */
data class SessionHead(
    val title: String,
    val model: String,
    val createdAtMs: Long,
)

/** 会话列表的整仓回执：能报头的按新在前排好；读不出头的文件单独数出来，不许装作不存在。 */
data class SessionListing(val heads: List<Pair<String, SessionHead>>, val unreadable: Int)

/**
 * 落盘的一条消息。role 只认三个真值：
 *  - user / assistant：真说过话的气泡；
 *  - error：我方对失败的说明卡——它照实存，但喂回模型时永远剔掉，
 *    「错误卡不进历史」的老规矩从内存一路贯到盘上。
 * incomplete 标半截回答：重载回来还带着这个标记，不拿断话冒充成品。
 */
data class StoredMsg(
    val role: String,
    val text: String,
    val atMs: Long,
    val incomplete: Boolean = false,
) {
    init {
        require(role == ROLE_USER || role == ROLE_ASSISTANT || role == ROLE_ERROR) {
            "消息角色只认 user/assistant/error，来了个「$role」"
        }
    }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_ERROR = "error"
    }
}

/** 一次读取的完整回执：坏了几行，数着报，不许悄悄丢。 */
data class LoadedSession(
    val id: String,
    val head: SessionHead?,
    val messages: List<StoredMsg>,
    val badLines: Int,
)

/** 喂模型的历史裁剪结果：砍了多少条、剔了几条错误/空话，界面必须拿这些数出声。 */
data class FeedResult(
    val feed: List<Pair<String, String>>,
    val trimmedCount: Int,
    val droppedNonFeedable: Int,
)

/**
 * 会话仓（M2 第一刀）：一个会话一个 JSONL 文件，杀进程重开还聊得下去。
 *
 * 格式定死成行式（首行会话头，之后每行一条消息），append 只加一行、不重写整文件——
 * 打字打到一半崩了，最坏丢那半行，读回来按坏行数着报，前头的字都还在。
 *
 * 为什么不用 SQLite：现在只有「整段读回、末尾追加」两个动作，JSONL 全中且零依赖；
 * 真要做搜索索引那天，迁移脚本从这份行式导出也干净。地基不提前盖二楼。
 *
 * 家规：
 *  - 本类纯 JVM，不碰 Android API——路径由调用方给，单测直接 java.io 跑；
 *  - 读不懂的行一律跳过并计数（load 的 badLines 是它的自证）；
 *  - 删会话是整文件删——不留「已删除」墓碑，那是搜索索引时代的烦恼。
 *
 * Kotlin 规矩记牢（CI 编译段抓过第四课）：String.indexOf 的谓词版
 * 是 `indexOf(predicate, startIndex)`——lambda 放第一位；
 * `indexOf(char, start) { ... }` 那第三参数是 ignoreCase: Boolean，不是谓词。
 */
class SessionStore(private val dir: File) {

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    /** 新会话：拿毫秒 + 进程内序号当 id，同毫秒撞不上。 */
    fun create(model: String): String {
        var id = "s" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet()
        var guard = 0
        while (file(id).exists()) {
            id = "s" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet()
            if (++guard > 64) error("新建会话撞名撞了 64 次，目录八成被人动过：$dir")
        }
        file(id).writeText(headJson(SessionHead("", model, System.currentTimeMillis())) + "\n")
        return id
    }

    fun exists(id: String): Boolean = file(id).exists()

    /** 会话落盘的完整路径（界面诊断、测试都要用；id 走同一套净化，出不了本目录）。 */
    fun pathOf(id: String): File = file(id)

    /** 追加一条：单行 JSONL，换行引号统一转义（jsonEscape 是唯一的写法出口）。 */
    fun append(id: String, msg: StoredMsg): Boolean {
        val f = file(id)
        if (!f.exists()) return false
        f.appendText(msgJson(msg) + "\n")
        return true
    }

    /**
     * 整写一个会话（迁移/导入用）：head 在前、消息按给定顺序逐行写。
     * 目标已存在就拒绝（返回 false）——导入不许悄悄盖掉用户手里的会话；
     * 先写 .tmp 再改名，中途崩了原文件不陪葬。转义只走 jsonEscape 一个出口，
     * 和 append 写出的行逐字节同款，load 读回来必须一条不少。
     */
    fun writeSession(id: String, head: SessionHead, messages: List<StoredMsg>): Boolean {
        val f = file(id)
        if (f.exists()) return false
        val body = StringBuilder(headJson(head)).apply {
            messages.forEach { append('\n').append(msgJson(it)) }
            append('\n')
        }
        val tmp = File(dir, f.name + ".tmp")
        return try {
            tmp.writeText(body.toString())
            tmp.renameTo(f) || run { tmp.delete(); false }
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    /** 给会话补标题（首条用户话截几个字由调用方决定，这里只负责改写头行）。 */
    fun rename(id: String, title: String): Boolean {
        val loaded = load(id) ?: return false
        val head = (loaded.head ?: SessionHead("", "", System.currentTimeMillis())).copy(title = title)
        val rewritten = StringBuilder(headJson(head)).apply {
            loaded.messages.forEach { append("\n").append(msgJson(it)) }
            append("\n")
        }
        // 先写旁再改名：中途崩了顶多留个 .tmp，不拿原会话陪葬
        val tmp = File(dir, file(id).name + ".tmp")
        tmp.writeText(rewritten.toString())
        if (!tmp.renameTo(file(id))) {
            tmp.delete()
            return false
        }
        return true
    }

    /**
     * 整读一个会话：坏行数着报；头行读不懂就 head=null（消息照给，界面自己决定怎么出声）。
     *
     * 头的资格只属于第一行非空行：那一行先按头解，解不动**再按消息解一次**——
     * 整文件没头的会话（老文件头行被删）消息一条都不该丢；两头文件里
     * 第二个头当坏行数出来，不拿后面的创建时间覆盖第一次。
     */
    fun load(id: String): LoadedSession? {
        val f = file(id)
        if (!f.exists()) return null
        var head: SessionHead? = null
        var headTried = false
        val msgs = ArrayList<StoredMsg>()
        var bad = 0
        f.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachLine
            if (!headTried) {
                headTried = true
                parseHead(line)?.let { head = it; return@forEachLine }
                // 首行不是头：不判死刑，落到消息解析再试一把
            }
            parseMsg(line)?.let { msgs += it } ?: run { bad++ }
        }
        return LoadedSession(id, head, msgs, bad)
    }

    /** 会话列表（新在前）；读不出头的文件算坏文件报个数，不装作不存在。 */
    fun list(): SessionListing {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: emptyArray()
        val heads = ArrayList<Pair<String, SessionHead>>(files.size)
        var unreadable = 0
        for (f in files) {
            val first = f.bufferedReader().use { it.readLine() }
            val head = first?.let { parseHead(it.trim()) }
            if (head == null) unreadable++ else heads += f.nameWithoutExtension to head
        }
        heads.sortByDescending { it.second.createdAtMs }
        return SessionListing(heads, unreadable)
    }

    /** 删会话：整文件删。返回是否真删掉了一个。 */
    fun delete(id: String): Boolean = file(id).delete()

    /**
     * 组喂模型的历史（带盘版）：
     *  - error 卡与空文本不进——教模型复述错误、拿断话冒充成品，两样都不干；
     *  - 超了 maxTurns 掐头留尾，**砍了几条如实报**，界面拿这个数出声。
     */
    fun feedFor(id: String, maxTurns: Int): FeedResult {
        val loaded = load(id) ?: return FeedResult(emptyList(), 0, 0)
        val feedable = loaded.messages.filter { it.role != StoredMsg.ROLE_ERROR && it.text.isNotBlank() }
        val dropped = loaded.messages.size - feedable.size
        val keep = feedable.takeLast(maxTurns.coerceAtLeast(0))
        return FeedResult(keep.map { it.role to it.text }, feedable.size - keep.size, dropped)
    }

    /** 只留文件名安全字符（字母数字点横杠下划线），其余换下划线：防路径穿越写盘。 */
    private fun file(id: String): File {
        val safe = id.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("").take(80)
        require(safe.isNotEmpty()) { "会话 id 不能是空的" }
        return File(dir, "$safe.jsonl")
    }

    // ── 手写 JSONL 编解码 ──────────────────────────────────────────────────
    // 为什么手写不引序列化库：只有五个字段的对象，转义规则一句话说清；
    // 引擎的依赖面越小，越不会有「升级序列化库」这种事。
    // 解析只认我们自己写的形状；读不懂返回 null 由调用方计数——宽容读取、严格写出。

    private fun headJson(h: SessionHead) = buildString {
        append("{\"k\":\"h\",")
        append("\"title\":").append(jsonEscape(h.title)).append(',')
        append("\"model\":").append(jsonEscape(h.model)).append(',')
        append("\"at\":").append(h.createdAtMs)
        append('}')
    }

    private fun msgJson(m: StoredMsg) = buildString {
        append("{\"k\":\"m\",")
        append("\"role\":").append(jsonEscape(m.role)).append(',')
        append("\"text\":").append(jsonEscape(m.text)).append(',')
        append("\"at\":").append(m.atMs)
        if (m.incomplete) append(",\"incomplete\":true")
        append('}')
    }

    private fun jsonEscape(s: String): String {
        val out = StringBuilder(s.length + 8).append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u").append(c.code.toString(16).padStart(4, '0')) else out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private fun parseHead(line: String): SessionHead? {
        val obj = simpleJson(line) ?: return null
        if (obj["k"] != "h") return null
        val title = obj["title"] ?: return null
        val model = obj["model"] ?: return null
        val at = obj["at"]?.toLongOrNull() ?: return null
        return SessionHead(title, model, at)
    }

    private fun parseMsg(line: String): StoredMsg? {
        val obj = simpleJson(line) ?: return null
        if (obj["k"] != "m") return null
        val role = obj["role"] ?: return null
        if (role != StoredMsg.ROLE_USER && role != StoredMsg.ROLE_ASSISTANT && role != StoredMsg.ROLE_ERROR) return null
        val text = obj["text"] ?: return null
        val at = obj["at"]?.toLongOrNull() ?: 0L
        val incomplete = obj["incomplete"] == "true"
        return StoredMsg(role, text, at, incomplete)
    }

    /**
     * 迷你 JSON 解析器：只认我们写出的那种「一行一个扁平对象」（值为字符串/数字/true）。
     * 认不了返回 null 当坏行——这里故意不做「通用 JSON」，通用是库的活，
     * 我们只需要读回自己写的东西，读不懂就数着报。
     */
    private fun simpleJson(line: String): Map<String, String>? {
        if (!line.startsWith("{") || !line.endsWith("}")) return null
        val body = line.substring(1, line.length - 1)
        if (body.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        var i = 0
        while (i < body.length) {
            // 读键（必为字符串）
            if (body[i] != '"') return null
            val keyEnd = indexOfQuote(body, i + 1)
            if (keyEnd < 0) return null
            val key = unescape(body.substring(i + 1, keyEnd))
            i = keyEnd + 1
            if (i >= body.length || body[i] != ':') return null
            i++
            // 读值：字符串 / true / 数字（其余按到逗号截断）
            when {
                body[i] == '"' -> {
                    val valEnd = indexOfQuote(body, i + 1)
                    if (valEnd < 0) return null
                    out[key] = unescape(body.substring(i + 1, valEnd))
                    i = valEnd + 1
                }
                body.startsWith("true", i) -> { out[key] = "true"; i += 4 }
                else -> {
                    var j = i
                    while (j < body.length && body[j] != ',') j++
                    out[key] = body.substring(i, j).trim()
                    i = j
                }
            }
            if (i < body.length) {
                if (body[i] != ',') return null
                i++
            }
        }
        return out
    }

    /** 找下一个「成对的」收尾引号：反斜杠连后一字一起跳过（\\ 不吃引号、\" 不算收尾）。 */
    private fun indexOfQuote(s: String, from: Int): Int {
        var j = from
        while (j < s.length) {
            when (s[j]) {
                '\\' -> j += 2
                '"' -> return j
                else -> j++
            }
        }
        return -1
    }

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') { out.append(c); i++; continue }
            i++
            if (i >= s.length) return s // 尾巴上半个转义：整串按原样退回，上层当坏行
            when (val e = s[i]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (i + 4 >= s.length) return s
                    val code = s.substring(i + 1, i + 5).toIntOrNull(16) ?: return s
                    out.append(code.toChar())
                    i += 4
                }
                else -> return s
            }
            i++
        }
        return out.toString()
    }

    private companion object {
        val SEQ = java.util.concurrent.atomic.AtomicInteger()
    }
}
