package com.hualuo.engine

import java.io.File
import java.util.regex.Pattern
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 机器闸门：**源码（.kt 与 .kts）里不许出现表情与符号，转义写法同样不许**。
 *
 * 家规（用户 2026-09-15 明确）：从来没人允许把表情放进代码；写成「反斜杠 u 加四位十六进制」
 * 也不行 —— 那还是同一个字符，只是看不见，而且给以后留坑：改坏一位就是编译错误，
 * 或者运行时变成一个孤立代理位（半个表情）。
 *
 * 判两件事：
 *  1) 裸码位：源码字节里直接出现被禁号段的字符；
 *  2) 转义写法：源码里出现「反斜杠 u + 四位十六进制」，且解出来的码位落在被禁号段。
 *     代理对必须**两段合起来判**（单独一段是 0xD800 段，看不出是表情）；
 *     并且只认**紧挨着**的两段，中间夹了别的字符就不算一对。
 *
 * 号段是逐段列出来的，其中几段是我自己查漏补上的：几何图形（播放键、方块、圆中点、尖角、圆点）
 * 与杂项技术（闹钟、漏沙、键盘、全屏方框）原先**不在**禁止范围里 —— 漏一段就等于对它们装看不见，
 * 那"扫干净"这句话就是假的。
 *
 * **写法教训（run 34904613303 就是这个红）**：带主语的 when 里分支只能写值或区间，
 * 不能写 `code == 0x200D` 这种布尔表达式 —— 它拿 true 跟 Int 比，编译直接崩。
 * 这个错我犯过两次，第二次是重写文件时把旧写法抄回去的。
 *
 * 中文与中文标点、常用排版符（行尾尖括号、乘号）不在禁止范围：界面文案和注释本来就该是中文。
 */
class NoEmojiInSourceTest {

    /** 一处命中：文件、行号、码位，以及它是裸字符还是转义写法。 */
    private data class Hit(val file: String, val line: Int, val codePoint: Int, val form: String) {
        fun describe(): String = "$file:$line ${form}码位 U+${"%04X".format(codePoint)}"
    }

    @Test
    fun kotlinSourcesContainNoEmojiEvenAsEscapes() {
        val root = repoRoot()
        val sources = findSources(root)
        val hits = ArrayList<Hit>()

        for (file in sources) {
            val relative = relative(root, file)
            var lineNo = 0
            // forEachLine 逐行读：不整文件进内存，也不碰被禁的读法
            file.forEachLine { line ->
                lineNo += 1
                scanLine(line, relative, lineNo, hits)
            }
        }

        if (hits.isNotEmpty()) {
            val byFile = hits.groupingBy { it.file }.eachCount()
            val summary = byFile.entries.sortedByDescending { it.value }
                .joinToString("\n  - ") { "${it.key} 共 ${it.value} 处" }
            val detail = hits.take(60).joinToString("\n  - ") { it.describe() }
            throw AssertionError(
                "源码里不许有表情与符号，转义写法也不行（要显示就放资源 + IconKey 键名）。" +
                    "命中 ${hits.size} 处，按文件汇总：\n  - $summary\n明细：\n  - $detail",
            )
        }
        assertTrue("闸门必须真扫到东西（扫到 0 个文件就是自己在装样子）", sources.isNotEmpty())
    }

    /** 一行里既查裸码位，也查转义写法。 */
    private fun scanLine(line: String, file: String, lineNo: Int, hits: ArrayList<Hit>) {
        var index = 0
        while (index < line.length) {
            val code = Character.codePointAt(line, index)
            if (isBannedCodePoint(code)) hits += Hit(file, lineNo, code, "裸")
            index += Character.charCount(code)
        }
        scanEscapes(line, file, lineNo, hits)
    }

    /** 把一行的转义写法解出来判：代理对要两段合起来才看得出是表情。 */
    private fun scanEscapes(line: String, file: String, lineNo: Int, hits: ArrayList<Hit>) {
        val matcher = ESCAPE.matcher(line)
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        val codes = ArrayList<Int>()
        while (matcher.find()) {
            starts += matcher.start()
            ends += matcher.end()
            codes += Integer.parseInt(matcher.group(1), 16)
        }

        var i = 0
        while (i < codes.size) {
            val code = codes[i]
            val next = if (i + 1 < codes.size) codes[i + 1] else -1
            val adjacent = i + 1 < codes.size && starts[i + 1] == ends[i]
            if (code in 0xD800..0xDBFF && adjacent && next in 0xDC00..0xDFFF) {
                val combined = Character.toCodePoint(code.toChar(), next.toChar())
                if (isBannedCodePoint(combined)) hits += Hit(file, lineNo, combined, "转义")
                i += 2
            } else {
                if (isBannedCodePoint(code)) hits += Hit(file, lineNo, code, "转义")
                i += 1
            }
        }
    }

    /**
     * 被禁号段。带主语的 when：分支只写值或区间，**不许**写 code == 某个值。
     */
    private fun isBannedCodePoint(code: Int): Boolean = when (code) {
        in 0x1F000..0x1FAFF -> true // 牌面、象形文字、图形扩展（含国旗）
        in 0x2600..0x27BF -> true // 杂项符号与装饰符号
        in 0x2190..0x21FF -> true // 箭头
        in 0x2B00..0x2BFF -> true // 方块箭头与几何扩展
        in 0xFE00..0xFE0F -> true // 变体选择符
        in 0x25A0..0x25FF -> true // 几何图形：播放键、方块、圆中点、尖角（原先漏了）
        in 0x2300..0x23FF -> true // 杂项技术符号：闹钟、漏沙、键盘、全屏方框（原先漏了）
        in 0x2100..0x214F -> true // 类字母符号：信息符之类（原先漏了）
        in 0x2900..0x297F -> true // 补充箭头
        in 0x2A00..0x2AFF -> true // 补充数学符号与箭头
        in 0x200D..0x200D -> true // 零宽连接符：表情组合用它
        in 0x20E3..0x20E3 -> true // 组合用键帽
        else -> false
    }

    @Test
    fun escapeFormIsRejectedJustLikeTheRawCharacter() {
        // 这条是用户这次纠正的核心：转义写法必须和裸字符一起判红
        val surrogatePair = ArrayList<Hit>()
        scanLine("val icon = \"" + ESCAPED_CYCLE_ARROW + "\"", "Sample.kt", 7, surrogatePair)
        assertTrue("代理对转义该被合成判出：$surrogatePair", surrogatePair.any { it.form == "转义" })
        assertTrue(
            "报的必须是合成后的码位而不是半段：$surrogatePair",
            surrogatePair.any { it.codePoint == CYCLE_ARROW_POINT },
        )
        assertLineIs(7, surrogatePair.first().line)

        val bmpEscape = ArrayList<Hit>()
        scanLine("Text(\"" + ESCAPED_TRIGRAM + "\")", "Sample.kt", 2, bmpEscape)
        assertTrue("单段转义（三横线）也要抓到：$bmpEscape", bmpEscape.any { it.codePoint == TRIGRAM_POINT })

        val raw = ArrayList<Hit>()
        scanLine("图标 " + CYCLE_ARROW + " 在这里", "Sample.kt", 3, raw)
        assertTrue("裸字符也该被抓到：$raw", raw.any { it.form == "裸" })
        assertLineIs(3, raw.first().line)
    }

    @Test
    fun geometricAndTechnicalBlocksAreCovered() {
        // 补上的号段要有反例可钉：这几个都是本仓真用过的图形，漏一个就等于白扫
        for (code in listOf(0x25B6, 0x25A0, 0x25C9, 0x25BE, 0x25CF, 0x25B8)) {
            assertTrue("几何图形该禁：U+${"%04X".format(code)}", isBannedCodePoint(code))
        }
        for (code in listOf(0x23F0, 0x23F1, 0x23F3, 0x2328, 0x26F6)) {
            assertTrue("技术符号该禁：U+${"%04X".format(code)}", isBannedCodePoint(code))
        }
        assertTrue("类字母符号该禁：U+2139", isBannedCodePoint(0x2139))
        assertTrue("零宽连接符该禁", isBannedCodePoint(0x200D))
        assertTrue("组合用键帽该禁", isBannedCodePoint(0x20E3))
    }

    @Test
    fun nonAdjacentSurrogateHalvesAreNotGluedIntoAPair() {
        // 两段中间夹了字符就不是一对，不许硬凑（凑出来的命中会让人查不到东西）
        val hits = ArrayList<Hit>()
        scanLine("broken " + ESCAPED_HIGH_HALF + "x" + ESCAPED_LOW_HALF + " here", "Sample.kt", 1, hits)
        assertTrue("不该合成成一对：$hits", hits.none { it.codePoint == CYCLE_ARROW_POINT })
    }

    @Test
    fun chineseAndAsciiStayAllowed() {
        // 反例：中文、中文标点、ASCII 与常用排版符必须放过，否则闸门就是捣乱
        val hits = ArrayList<Hit>()
        scanLine("网关掐了自动重发一次（默认关），temp=0.7", "Sample.kt", 1, hits)
        scanLine("上限 1 MiB；键名 ui.retry_costly_on_gateway", "Sample.kt", 2, hits)
        scanLine("行尾尖括号 › 与乘号 × 属排版符号：a × b ›", "Sample.kt", 3, hits)
        assertTrue("中文与 ASCII 不该命中：$hits", hits.isEmpty())
        assertFalse(isBannedCodePoint('中'.code))
        assertFalse(isBannedCodePoint('。'.code))
        assertFalse(isBannedCodePoint('《'.code))
        assertFalse(isBannedCodePoint(0x203A)) // › 行尾排版符
        assertFalse(isBannedCodePoint(0x00D7)) // × 乘号
    }

    @Test
    fun bannedRangesCoverTheRealIconBlocks() {
        // 钉住判定表：这些号段就是图形字符住的地方，谁删一段都得有理由
        for (code in listOf(0x1F600, 0x1F501, 0x2705, 0x2630, 0x2192, 0x2B06, 0xFE0F, 0x200D, 0x20E3)) {
            assertTrue("该禁：U+${"%04X".format(code)}", isBannedCodePoint(code))
        }
    }

    private fun assertLineIs(expected: Int, actual: Int) {
        if (expected != actual) throw AssertionError("行号报错必须真实：期望 $expected 实得 $actual")
    }

    private fun findSources(root: File): List<File> = File(root, "engine").walkTopDown()
        .onEnter { dir -> dir.name != "build" && dir.name != ".git" && dir.name != ".gradle" }
        .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
        .toList()

    private fun relative(root: File, file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).trimStart(File.separatorChar)

    /** 从模块目录往上找仓库根（测试工作目录是 engine 模块）。找不到就抛，不许静默放行。 */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        var steps = 0
        while (dir != null && steps < 5) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            steps += 1
        }
        throw AssertionError("找不到仓库根（没有 settings.gradle.kts），闸门不许静默放行")
    }

    private companion object {
        /** 一个字面反斜杠。拼出来用，源码里不留被禁写法的字面量。 */
        const val BACKSLASH: String = "\\"

        /** 字母 u。与被禁写法拆成两段，源码自身才不会被自己判红。 */
        const val LETTER_U: String = "u"

        const val CYCLE_ARROW_POINT: Int = 0x1F501
        const val TRIGRAM_POINT: Int = 0x2630

        /** 正则：字面反斜杠加 u 再加四位十六进制。正则里要两个反斜杠才匹配一个字面反斜杠。 */
        val ESCAPE: Pattern = Pattern.compile(BACKSLASH + BACKSLASH + LETTER_U + "([0-9a-fA-F]" + "{4})")

        /** 运行时才是「反斜杠 u D83D 反斜杠 u DD01」这段文本。 */
        val ESCAPED_CYCLE_ARROW: String = escaped(0xD83D) + escaped(0xDD01)

        val ESCAPED_HIGH_HALF: String = escaped(0xD83D)
        val ESCAPED_LOW_HALF: String = escaped(0xDD01)
        val ESCAPED_TRIGRAM: String = escaped(TRIGRAM_POINT)

        /** 运行时才是那个循环箭头字符。 */
        val CYCLE_ARROW: String = String(Character.toChars(CYCLE_ARROW_POINT))

        fun escaped(codeUnit: Int): String = BACKSLASH + LETTER_U + "%04X".format(codeUnit)
    }
}
