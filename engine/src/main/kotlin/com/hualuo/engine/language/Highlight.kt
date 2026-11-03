package com.hualuo.engine.language

/**
 * 轻量染色器（查看器第二件）：把文本按 [LangRegistry.Lang] 切成 token 段，
 * app 层拿 span 染色。行式扫描+跨行块注释状态机，纯 JVM 可测。
 *
 * 刻意不引语法树：查看器的目标是「看得清」，注释/字符串/数字/关键词四色足够；
 * 大而全的语法高亮库是供应链风险，换来的只是慢。
 */
object Highlight {

    enum class Kind { PLAIN, COMMENT, STRING, NUMBER, KEYWORD }

    /** 一段着色区间：[start, end) 是该行内的字符下标。 */
    data class Span(val start: Int, val end: Int, val kind: Kind)

    /** 一行的着色账。 */
    data class LineSpans(val spans: List<Span>)

    /**
     * 全文染色：按 \n 切行（保留行内原有缩进，不改写内容——红线：查看器只看不改），
     * 块注释跨行时状态带下去。每行输出 [LineSpans]。
     */
    fun highlight(text: String, lang: LangRegistry.Lang): List<LineSpans> {
        val lines = text.split('\n')
        val out = ArrayList<LineSpans>(lines.size)
        var inBlockComment = false
        var blockEnd: String? = lang.blockComment?.second
        for (line in lines) {
            val r = scanLine(line, lang, inBlockComment, blockEnd)
            out += LineSpans(r.spans)
            inBlockComment = r.inBlockAfter
        }
        return out
    }

    private class ScanResult(val spans: List<Span>, val inBlockAfter: Boolean)

    private fun scanLine(
        line: String,
        lang: LangRegistry.Lang,
        inBlock: Boolean,
        blockEnd: String?,
    ): ScanResult {
        val spans = ArrayList<Span>()
        var i = 0
        var inBlockNow = inBlock
        val n = line.length
        var plainStart = 0

        fun flushPlain(upto: Int) {
            if (upto > plainStart) spans += Span(plainStart, upto, Kind.PLAIN)
        }

        while (i < n) {
            if (inBlockNow) {
                val end: String = blockEnd ?: ""
                val stop = if (end.isNotEmpty()) line.indexOf(end, i) else -1
                if (stop < 0) {
                    spans += Span(plainStart, n, Kind.COMMENT)
                    return ScanResult(spans, true)
                }
                val stopEnd = stop + end.length
                spans += Span(plainStart, stopEnd, Kind.COMMENT)
                i = stopEnd
                plainStart = i
                inBlockNow = false
                continue
            }
            // 行注释：到行尾
            val lc = lang.lineComments.firstOrNull { line.startsWith(it, i) && it.isNotEmpty() }
            if (lc != null) {
                flushPlain(i)
                spans += Span(i, n, Kind.COMMENT)
                return ScanResult(spans, false)
            }
            // 块注释：开
            val bc = lang.blockComment
            if (bc != null && line.startsWith(bc.first, i)) {
                flushPlain(i)
                val close = line.indexOf(bc.second, i + bc.first.length)
                if (close < 0) {
                    spans += Span(i, n, Kind.COMMENT)
                    return ScanResult(spans, true)
                }
                val end = close + bc.second.length
                spans += Span(i, end, Kind.COMMENT)
                i = end
                plainStart = i
                continue
            }
            // 字符串：找到配对引号（同字符成对）
            val delim = lang.stringDelims.firstOrNull { it.length == 1 && line[i] == it[0] }
            if (delim != null) {
                flushPlain(i)
                var j = i + 1
                while (j < n) {
                    if (line[j] == '\\' && j + 1 < n) { j += 2; continue }
                    if (line[j] == delim[0]) { j++; break }
                    j++
                }
                spans += Span(i, minOf(j, n), Kind.STRING)
                i = minOf(j, n)
                plainStart = i
                continue
            }
            // 数字：词首为数字
            if (line[i].isDigit()) {
                flushPlain(i)
                var j = i
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '.' || line[j] == '_' ||
                            ((line[j] == '+' || line[j] == '-') && j > i && (line[j - 1] == 'e' || line[j - 1] == 'E')))
                ) j++
                spans += Span(i, j, Kind.NUMBER)
                i = j
                plainStart = i
                continue
            }
            // 关键词：整词匹配
            if (line[i].isLetter() || line[i] == '_') {
                var j = i
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                val word = line.substring(i, j)
                if (lang.keywords.contains(word)) {
                    flushPlain(i)
                    spans += Span(i, j, Kind.KEYWORD)
                    i = j
                    plainStart = i
                    continue
                }
                i = j
                continue
            }
            i++
        }
        flushPlain(n)
        return ScanResult(spans, inBlockNow)
    }
}
