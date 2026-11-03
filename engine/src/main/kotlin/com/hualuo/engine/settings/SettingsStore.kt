package com.hualuo.engine.settings

/**
 * 通用键值设置存储：把「读得懂、留得住、坏了会出声、存了真存上」四件事做实在。
 *
 * 逐条对着旧 Agora 的病来：
 *  1) **未知键原样保留**，保存时一并写回（连不合规的键名也留着）。反的是当年写路径
 *     `MessagePersistenceGuard.sanitize()` 贪心砍最大字段、还往正文里塞截断标记那种
 *     「存进去的和拿出来的不是同一份内容」的做法。
 *  2) **坏消息一条不吞**：坏行、坏值、重复键、越界、孤立代理位全部进 [issues]，程序照常用默认值/
 *     夹到边界继续跑，但不装没事。反的是「步骤绿了、其实什么都没干」的静默失败。
 *  3) **[save] 带原因**：后端写失败时返回 `persisted=false` 加具体原因，绝不再造第二种
 *     「绿了但没落盘」的假成功。
 *
 * 格式：UTF-8 文本，`#` 开头是注释，其余 `key=value`。键名限 ASCII 点分小写
 * （如 `tool.idle_timeout_seconds`，最长 [MAX_KEY_LENGTH] 字符），值允许中文与表情。
 * 值里的控制字符一律转义（`\n` `\r` `\t` `\\` `\uXXXX`）；**半个表情（孤立代理位）也转义**，
 * 因为 UTF-8 编不出它，写进文件就是烂字节 —— 读回来照样出声，但内容逐字不改。
 * 行尾空白会被去掉（值里的前导空格保留）。
 *
 * 线程模型：**非线程安全**。设置读写都收敛在单一线程（界面线程）；跨线程请外部串行化。
 */
class SettingsStore(private val storage: SettingsStorage) {

    private val values = LinkedHashMap<String, String>()
    private val collected = ArrayList<SettingsIssue>()
    private var dirty = false

    init {
        reload()
    }

    // ── 载入 ────────────────────────────────────────────────────────────────

    /** 从后端重读一份，丢掉尚未保存的改动。后端没内容等于空表，不算错误。 */
    fun reload(): LoadReport {
        val text = storage.read()
        return if (text == null) {
            values.clear()
            collected.clear()
            dirty = false
            LoadReport(hadContent = false, keyCount = 0, issueCount = 0)
        } else {
            adoptText(text)
        }
    }

    /** 用内存里的一份文本载入（从备份恢复、或者测试直接喂样本时用）。不碰后端。 */
    fun loadText(text: String): LoadReport = adoptText(text)

    private fun adoptText(text: String): LoadReport {
        val parsed = LinkedHashMap<String, String>()
        val found = ArrayList<SettingsIssue>()
        parseInto(text, parsed, found)
        values.clear()
        values.putAll(parsed)
        collected.clear()
        collected.addAll(found)
        dirty = false
        return LoadReport(hadContent = true, keyCount = parsed.size, issueCount = found.size)
    }

    // ── 坏消息 ──────────────────────────────────────────────────────────────

    /** 已累计的坏消息（载入时发现的，加上取值时新记的）。 */
    fun issues(): List<SettingsIssue> = collected.toList()

    /** 取出并清空坏消息：界面弹一次就够，同一条不该反复刷屏。 */
    fun drainIssues(): List<SettingsIssue> {
        val copy = collected.toList()
        collected.clear()
        return copy
    }

    // ── 只读视图 ────────────────────────────────────────────────────────────

    fun keys(): Set<String> = values.keys.toSet()
    fun size(): Int = values.size
    fun has(key: String): Boolean = values.containsKey(key)
    fun raw(key: String): String? = values[key]

    /** 有改动还没落盘（自动保存轮询与「退出前提醒」都看这个）。 */
    fun isDirty(): Boolean = dirty

    /** 当前内容的文本形式（不碰后端）。 */
    fun text(): String = serialize()

    // ── 取值 ────────────────────────────────────────────────────────────────

    fun string(key: String, default: String = ""): String = values[key] ?: default

    fun boolean(key: String, default: Boolean): Boolean {
        val stored = values[key] ?: return default
        return when (stored.trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> {
                collected += SettingsIssue.BadValue(key, "布尔", stored)
                default
            }
        }
    }

    fun int(key: String, default: Int): Int =
        intInRange(key, default, Int.MIN_VALUE, Int.MAX_VALUE)

    /** 取整数并夹到 [min]..[max]：越界夹到边界、读不懂用默认值，两种都要出声。 */
    fun intInRange(key: String, default: Int, min: Int, max: Int): Int {
        require(min <= max) { "min 不能大于 max（$min > $max）" }
        val stored = values[key] ?: return default
        val number = stored.trim().toIntOrNull()
        if (number == null) {
            collected += SettingsIssue.BadValue(key, "整数", stored)
            return default
        }
        if (number < min || number > max) {
            val clamped = if (number < min) min else max
            collected += SettingsIssue.OutOfRange(
                key = key,
                raw = stored,
                clampedTo = clamped.toLong(),
                min = min.toLong(),
                max = max.toLong(),
            )
            return clamped
        }
        return number
    }

    fun long(key: String, default: Long): Long {
        val stored = values[key] ?: return default
        return stored.trim().toLongOrNull() ?: run {
            collected += SettingsIssue.BadValue(key, "长整数", stored)
            default
        }
    }

    fun double(key: String, default: Double): Double {
        val stored = values[key] ?: return default
        return stored.trim().toDoubleOrNull() ?: run {
            collected += SettingsIssue.BadValue(key, "小数", stored)
            default
        }
    }

    // ── 改值 ────────────────────────────────────────────────────────────────

    /**
     * 写一个值。键名不合法是调用方写错代码，直接抛，不做「悄悄换个名字存」。
     * 值里有孤立代理位不抛（那是调用方从模型/文件里拿来的），但当场记一条坏消息。
     */
    fun setString(key: String, value: String) {
        requireValidKey(key)
        val lone = firstUnpairedSurrogate(value)
        if (lone >= 0) {
            collected += SettingsIssue.LoneSurrogate(key, value[lone].code)
        }
        if (values[key] == value) return
        values[key] = value
        dirty = true
    }

    fun setInt(key: String, value: Int) = setString(key, value.toString())

    fun setLong(key: String, value: Long) = setString(key, value.toString())

    fun setDouble(key: String, value: Double) = setString(key, value.toString())

    fun setBoolean(key: String, value: Boolean) =
        setString(key, if (value) TRUE_TEXT else FALSE_TEXT)

    /** 删一个键，返回是不是真删掉了。 */
    fun remove(key: String): Boolean {
        if (!values.containsKey(key)) return false
        values.remove(key)
        dirty = true
        return true
    }

    /** 批量删，返回实际删掉的数量（草稿发出去之后清草稿用）。 */
    fun removeKeys(keys: Collection<String>): Int = keys.count { remove(it) }

    // ── 落盘 ────────────────────────────────────────────────────────────────

    /** 全量写回后端。失败带原因返回，不抛异常。 */
    fun save(): SaveResult {
        val body = serialize()
        return try {
            storage.write(body)
            dirty = false
            SaveResult(persisted = true, keyCount = values.size, charCount = body.length, failure = null)
        } catch (e: Exception) {
            SaveResult(
                persisted = false,
                keyCount = values.size,
                charCount = body.length,
                failure = "${e.javaClass.simpleName}: ${e.message ?: "（无消息）"}",
            )
        }
    }

    /** 只在真有改动时落盘（自动保存用），没改动返回 null，不制造无谓写盘。 */
    fun saveIfDirty(): SaveResult? = if (dirty) save() else null

    // ── 序列化 ──────────────────────────────────────────────────────────────

    private fun serialize(): String {
        val builder = StringBuilder()
        builder.append("# 华络仓库工具 · 设置（可手工编辑；未知键与不合规键名都会原样留回）\n")
        builder.append("#format=").append(CURRENT_FORMAT).append('\n')
        for ((key, value) in values) {
            builder.append(key).append('=').append(escapeValue(value)).append('\n')
        }
        return builder.toString()
    }

    private fun parseInto(
        text: String,
        into: LinkedHashMap<String, String>,
        found: ArrayList<SettingsIssue>,
    ) {
        var lineNumber = 0
        for (rawLine in text.lineSequence()) {
            lineNumber += 1
            val line = rawLine.trimStart()
            when {
                line.isEmpty() -> continue
                line.startsWith("#") -> readMarker(line, lineNumber, found)
                else -> readPair(line, lineNumber, into, found)
            }
        }
    }

    private fun readMarker(line: String, lineNumber: Int, found: ArrayList<SettingsIssue>) {
        if (!line.startsWith(FORMAT_PREFIX)) return
        val declared = line.substring(FORMAT_PREFIX.length).trim().toIntOrNull()
        when {
            declared == null -> found += SettingsIssue.BadFormatMarker(lineNumber, line)
            declared > CURRENT_FORMAT -> found += SettingsIssue.NewerFormat(lineNumber, declared, CURRENT_FORMAT)
        }
    }

    private fun readPair(
        line: String,
        lineNumber: Int,
        into: LinkedHashMap<String, String>,
        found: ArrayList<SettingsIssue>,
    ) {
        val eq = line.indexOf('=')
        if (eq < 0) {
            found += SettingsIssue.MissingEquals(lineNumber, line)
            return
        }
        val key = line.substring(0, eq).trim()
        if (key.isEmpty()) {
            found += SettingsIssue.EmptyKey(lineNumber)
            return
        }
        if (!KEY_PATTERN.matches(key)) {
            found += SettingsIssue.IllegalKey(lineNumber, key)
        }
        if (into.containsKey(key)) {
            found += SettingsIssue.DuplicateKey(lineNumber, key)
        }
        val value = unescape(line.substring(eq + 1).trimEnd(), lineNumber, found)
        val lone = firstUnpairedSurrogate(value)
        if (lone >= 0) {
            found += SettingsIssue.LoneSurrogate(key, value[lone].code)
        }
        into[key] = value
    }

    private fun unescape(
        raw: String,
        lineNumber: Int,
        found: ArrayList<SettingsIssue>,
    ): String {
        if (raw.indexOf('\\') < 0) return raw
        val builder = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char != '\\') {
                builder.append(char)
                index += 1
                continue
            }
            if (index + 1 >= raw.length) {
                found += SettingsIssue.BadEscape(lineNumber, "行尾多了一个反斜杠")
                builder.append(char)
                index += 1
                continue
            }
            val next = raw[index + 1]
            when (next) {
                'n' -> { builder.append('\n'); index += 2 }
                'r' -> { builder.append('\r'); index += 2 }
                't' -> { builder.append('\t'); index += 2 }
                '\\' -> { builder.append('\\'); index += 2 }
                'u' -> {
                    if (index + 6 > raw.length) {
                        found += SettingsIssue.BadEscape(lineNumber, "\\u 后面不足四位十六进制")
                        builder.append(next)
                        index += 2
                    } else {
                        val digits = raw.substring(index + 2, index + 6)
                        val code = digits.toIntOrNull(16)
                        if (code == null) {
                            found += SettingsIssue.BadEscape(lineNumber, "\\u$digits 不是合法十六进制")
                            builder.append(next)
                            index += 2
                        } else {
                            builder.append(code.toChar())
                            index += 6
                        }
                    }
                }
                else -> {
                    found += SettingsIssue.BadEscape(lineNumber, "不认识的转义 \\$next")
                    builder.append(char).append(next)
                    index += 2
                }
            }
        }
        return builder.toString()
    }

    private fun escapeValue(value: String): String {
        val builder = StringBuilder(value.length + 8)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            val code = char.code
            when {
                char == '\\' -> { builder.append("\\\\"); index += 1 }
                char == '\n' -> { builder.append("\\n"); index += 1 }
                char == '\r' -> { builder.append("\\r"); index += 1 }
                char == '\t' -> { builder.append("\\t"); index += 1 }
                code < 0x20 -> { builder.append("\\u").append(hex4(code)); index += 1 }
                code in HIGH_SURROGATE_FIRST..HIGH_SURROGATE_LAST -> {
                    val pair = if (index + 1 < value.length) value[index + 1].code else -1
                    if (pair in LOW_SURROGATE_FIRST..LOW_SURROGATE_LAST) {
                        builder.append(char).append(value[index + 1])
                        index += 2
                    } else {
                        builder.append("\\u").append(hex4(code))
                        index += 1
                    }
                }
                code in LOW_SURROGATE_FIRST..LOW_SURROGATE_LAST -> {
                    builder.append("\\u").append(hex4(code))
                    index += 1
                }
                else -> { builder.append(char); index += 1 }
            }
        }
        return builder.toString()
    }

    /** 第一个「没配对」的代理位下标；干净就返回 -1。 */
    private fun firstUnpairedSurrogate(text: String): Int {
        var index = 0
        while (index < text.length) {
            val code = text[index].code
            if (code in HIGH_SURROGATE_FIRST..HIGH_SURROGATE_LAST) {
                val next = if (index + 1 < text.length) text[index + 1].code else -1
                if (next in LOW_SURROGATE_FIRST..LOW_SURROGATE_LAST) {
                    index += 2
                    continue
                }
                return index
            }
            if (code in LOW_SURROGATE_FIRST..LOW_SURROGATE_LAST) return index
            index += 1
        }
        return -1
    }

    private fun hex4(code: Int): String =
        "${HEX_DIGITS[(code shr 12) and 15]}${HEX_DIGITS[(code shr 8) and 15]}" +
            "${HEX_DIGITS[(code shr 4) and 15]}${HEX_DIGITS[code and 15]}"

    private fun requireValidKey(key: String) {
        require(key.length <= MAX_KEY_LENGTH) { "设置键太长（${key.length} > $MAX_KEY_LENGTH）：$key" }
        require(KEY_PATTERN.matches(key)) {
            "设置键必须是 ASCII 点分小写（如 tool.idle_timeout_seconds），当前=$key"
        }
    }

    companion object {
        /** 文件格式版本，只在旧代码读不懂新格式时才加。 */
        const val CURRENT_FORMAT = 1

        /** 键名长度上限。 */
        const val MAX_KEY_LENGTH = 64

        /** 布尔值的规范写法（读的时候宽容，写的时候统一）。 */
        const val TRUE_TEXT = "true"
        const val FALSE_TEXT = "false"

        private const val FORMAT_PREFIX = "#format="
        private const val HEX_DIGITS = "0123456789abcdef"
        private const val HIGH_SURROGATE_FIRST = 0xD800
        private const val HIGH_SURROGATE_LAST = 0xDBFF
        private const val LOW_SURROGATE_FIRST = 0xDC00
        private const val LOW_SURROGATE_LAST = 0xDFFF
        private val KEY_PATTERN = Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*")
    }
}
