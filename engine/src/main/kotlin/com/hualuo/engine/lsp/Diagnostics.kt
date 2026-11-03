package com.hualuo.engine.lsp

/**
 * 一条编译/诊断条目（行/列全部 1 起，未知列记 0——不乱编）。
 *
 * 为什么自己做模型而不是直接把编译器输出丢给界面：解析与展示分开之后，
 * 「解析器认出了几条、跳过了几条」成了可测、可出声的账——不许把认不出的输出
 * 悄悄丢成「没问题」（假绿的一种）。
 */
data class Diagnostic(
    /** 文件路径（编译器给的原样；能不能对上仓库路径由上层判）。 */
    val file: String,
    /** 1 起的行号；0 = 编译器没说（比如整体链接错误）。 */
    val line: Int,
    /** 1 起的列号；0 = 编译器没说。 */
    val column: Int,
    /** 严重度：error / warning / note / info（原样归一化小写）。 */
    val severity: String,
    /** 给人看的一句话。 */
    val message: String,
    /** 产出这条输出的工具名（gcc / kotlinc / rustc …），出错时对得上人。 */
    val source: String,
)

/** 一次诊断收场：条目 + 认不出 / 截断的账（都必须能说出来）。 */
data class DiagnosticReport(
    val items: List<Diagnostic>,
    /** 有输出行但解析器一条都认不出时的原始片段（前几行），让人能查。 */
    val unparsedExcerpt: String?,
    /** 解析不出成条目、但也没法忽略的行数（诚实记账）。 */
    val skippedLines: Int,
    /** 是否因为条数上限被截断。 */
    val truncated: Boolean,
    /** 退出码原样带上（工具链判词的一部分）。 */
    val exitCode: Int?,
)

/**
 * 诊断输出解析器：六族编译器 / 检查器的输出形状（纯函数，纯 JVM 好测）。
 *
 * 形状速览（都按「一条诊断一行」的常见形态抽）：
 *  - gcc/clang：`path:line:col: error: msg`（也有 `warning:` `note:`；无列时 `path:line: msg`）
 *  - javac：`path:line: error: msg`
 *  - kotlinc CLI：`path:line:col: error: msg`（与 gcc 同形；另有 `path:line: error: msg`）
 *  - rustc：两行式——`error[E0308]: msg` 换行跟着 ` --> path:line:col`
 *  - go：`path:line:col: msg`（无 severity 词；错/警告靠缩进与上下文）
 *  - tsc：`path(line,col): error TS1234: msg`
 *
 * 纪律：
 *  - 认不出的行**计数**（[DiagnosticReport.skippedLines]）不静默；
 *  - 条数封顶 [MAX_ITEMS]，截断必须说出来；
 *  - 退出码为 0 且一条没认出时，那是「真干净」不是「解析器瞎了」——
 *    调用方结合 [DiagnosticReport.unparsedExcerpt] 判，解析器不替它下结论；
 *  - 两类**假条目**当场掐掉：`-->` 定位行（rustc 两行式的零件，不是诊断）、
 *    没有 severity 词又纯数字的「消息」（那是 `file:line:col` 位置片段的尾数，不是话）。
 *    这两条是重写时补的：不加它们，一个位置片段会被装成一条严重度为 error 的假诊断。
 */
object DiagnosticParser {

    /** 一次解析的条数上限（超了截断并出声）。 */
    const val MAX_ITEMS = 300

    /** 六族通吃的解析入口。[output] = stdout + stderr 合并文本（编译器喜欢分两路，调用方合并）。 */
    fun parse(output: String, tool: String, exitCode: Int? = null): DiagnosticReport {
        if (output.isBlank()) return DiagnosticReport(emptyList(), null, 0, false, exitCode)
        val items = ArrayList<Diagnostic>()
        var skipped = 0
        var truncated = false
        val lines = output.split('\n')
        val t = tool.lowercase()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd('\r')
            if (line.isBlank()) { i += 1; continue }
            if (items.size >= MAX_ITEMS) { truncated = true; break }
            val parsed = when (t) {
                "rustc", "cargo" -> parseRustcLine(line, lines.getOrNull(i + 1))
                else -> parseSingleLine(line, t)?.let { ParsedLine(it, false) }
            }
            when (parsed) {
                null -> skipped += 1
                else -> {
                    items += parsed.diagnostic
                    if (parsed.consumedNext) i += 1
                }
            }
            i += 1
        }
        val excerpt = if (items.isEmpty() && skipped > 0) {
            lines.filter { it.isNotBlank() }.take(5).joinToString("\n")
        } else {
            null
        }
        return DiagnosticReport(items, excerpt, skipped, truncated, exitCode)
    }

    /** 一行解析的产物：[diagnostic] + 是否吃掉了下一行（rustc 两行式）。 */
    private data class ParsedLine(val diagnostic: Diagnostic, val consumedNext: Boolean)

    /** 单行形状（gcc/javac/kotlinc/go/tsc，以及 rustc 的单行形态）。 */
    private fun parseSingleLine(line: String, tool: String): Diagnostic? {
        // `-->` 定位行是 rustc 两行式的零件，不是诊断：单行路径遇到就跳过（不吃成假条目）。
        if (line.trimStart().startsWith("-->")) return null
        // tsc：`path(line,col): error TS1234: msg`
        tscShape.find(line)?.let { m ->
            return Diagnostic(
                file = m.groupValues[1],
                line = m.groupValues[2].toIntOrNull() ?: 0,
                column = m.groupValues[3].toIntOrNull() ?: 0,
                severity = severityOf(m.groupValues[4]),
                message = m.groupValues[5].trim(),
                source = tool,
            )
        }
        // 通用：`path:line:col: severity: msg` 或 `path:line:col: msg` 或 `path:line: severity: msg`
        colonShape.find(line)?.let { m ->
            val sevRaw = m.groupValues[4].trim()
            val msg = m.groupValues[5].trim()
            if (msg.isEmpty()) return null
            // 没有 severity 词又纯数字的「消息」= 位置片段的尾数（`a.kt:5:3` 的烂形），不是话。
            if (sevRaw.isEmpty() && msg.all { it.isDigit() }) return null
            return Diagnostic(
                file = m.groupValues[1],
                line = m.groupValues[2].toIntOrNull() ?: 0,
                column = m.groupValues[3].toIntOrNull() ?: 0,
                severity = severityOf(sevRaw.ifEmpty { "error" }),
                message = msg,
                source = tool,
            )
        }
        return null
    }

    /** rustc 两条式：`error[E0308]: msg` + 下一行 `  --> path:line:col`。 */
    private fun parseRustcLine(line: String, next: String?): ParsedLine? {
        val head = rustHead.find(line)
            ?: return parseSingleLine(line, "rustc")?.let { ParsedLine(it, false) }
        val severity = severityOf(head.groupValues[1])
        val message = head.groupValues[2].trim()
        val target = next?.let { rustArrow.find(it) }
            ?: return ParsedLine(Diagnostic("", 0, 0, severity, message, "rustc"), false)
        return ParsedLine(
            Diagnostic(
                file = target.groupValues[1],
                line = target.groupValues[2].toIntOrNull() ?: 0,
                column = target.groupValues[3].toIntOrNull() ?: 0,
                severity = severity,
                message = message,
                source = "rustc",
            ),
            consumedNext = true,
        )
    }

    /** 归一化严重度（不认识的原样小写保留——别把信息改没了）。 */
    private fun severityOf(raw: String): String = when (raw.lowercase()) {
        "error", "fatal", "fatal error" -> "error"
        "warning", "warn" -> "warning"
        "note", "help" -> "note"
        else -> raw.lowercase()
    }

    // 形状正则（都从行首/允许前置空白起匹配，避免吃进正文里的冒号）
    private val tscShape = Regex("""^\s*(.+?)\((\d+),(\d+)\):\s*(\w+)\s*[\w\d]*:\s*(.+)$""")
    private val colonShape = Regex("""^\s*(.+?):(\d+)(?::(\d+))?:\s*(error|warning|warn|note|fatal(?: error)?)?:?\s*(.+)$""")
    private val rustHead = Regex("""^(error|warning|note)(?:\[[^\]]+\])?:\s*(.+)$""")
    private val rustArrow = Regex("""^\s*-->\s*(.+?):(\d+):(\d+)\s*$""")
}
