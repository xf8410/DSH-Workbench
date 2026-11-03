package com.hualuo.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词法配平闸门（反假绿三闸门之一）。
 *
 * 背景：旧仓有过「脚本改 Kotlin，靠括号和转义逃逸骗过构建」的事故（"u50" 引号被吃，
 * run 30776666129；原型 v11f 的 `"]]],` 烂成乱码字节也是同款）。编译器能挡「括号不配平到
 * 编译不过」，但挡不住这些「还能绿」的疤：半个表情（孤立代理对）、U+FFFD 替换符、
 * 没闭合却一路吞到文件尾的注释。所以这里用独立状态机把全仓 .kt 再扫一遍——
 * 机器判「刀痕配不配平」，不采信任何说词。
 *
 * 状态机口径：
 *   - 行注释、块注释（Kotlin 块注释可嵌套，深度要配平）；
 *   - 普通串（反斜杠转义跳两格；串里见裸换行=引号被吃的指纹）；
 *   - 原生三引号串（无反斜杠转义；三引号先于单引号判定）；
 *   - 字符单引号；
 *   - 串内模板美元花括号：进串时压 T 帧并切 CODE，CODE 见右花括号且栈顶是 T 则弹回串；
 *   - 圆/方/花括号栈：文件尾必须全空；
 *   - 全文件禁孤立代理对与 U+FFFD。
 *
 * 配套纪律：detectorBitesOnKnownBadSamples 是变异自测——闸门若不会咬已知坏样本，
 * 它对全仓的「通过」就一文不值。
 */
class SourceHygieneTest {

    private data class Frame(val kind: Char, val line: Int)

    @Test
    fun allKotlinSourcesAreLexicallyBalanced() {
        val roots = listOf(
            File("..", "engine/src/main/kotlin"),
            File("..", "engine/src/test/kotlin"),
        )
        val problems = mutableListOf<String>()
        var scanned = 0
        for (root in roots) {
            assertTrue("找不到源码目录：$root —— 目录被挪了要报错，不许静默跳过", root.isDirectory)
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                scanned++
                checkFile(f.path.substringAfter("kotlin/"), f.readText(), problems)
            }
        }
        assertTrue("只扫到 $scanned 个 .kt（少于 20 说明文件被删了或路径错了，这也是事故）", scanned >= 20)
        assertTrue(
            "发现词法不配平/转义疤（共 ${problems.size} 处）：\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    /** 变异自测：每种已知疤必须被咬住，正常样本必须放行。 */
    @Test
    fun detectorBitesOnKnownBadSamples() {
        // 1) 引号被吃：串没闭合撞换行（"u50 事故的形态之一）
        expectProblem("val s = \"abc\nval t = 1\n", "字符串没闭合")
        // 2) 括号不配平：多一个闭括号
        expectProblem("fun a() { println(1) }\nval b = )\n", "找不到开括号")
        // 3) 括号错配：开圆闭花
        expectProblem("val c = (1}\n", "不配")
        // 4) 块注释吞到文件尾（刀把星斜吃掉的样子）
        expectProblem("val d = 1 /* never closed\n", "EOF 模式未闭合")
        // 5) 半个表情：孤立高代理（v11f 烂字节指纹）
        expectProblem("val e = \"x\uD83D\"\n", "孤立高代理")
        // 6) 乱码替换符疤
        expectProblem("val f = \"y\uFFFDz\"\n", "U+FFFD")
        // 正常样本必须全绿：模板、嵌套注释、转义引号、原生串、字符字面量
        expectClean("fun ok() {\n  /* 外 /* 内 */ 仍在外 */\n  val g = \"a\\nb ${'$'}{1 + 2}\"\n  val h = \"\"\"raw \"x\" \"\"\"\n  val i = '\\''\n  println(g + h + i)\n}\n")
    }

    private fun expectProblem(src: String, needle: String) {
        val problems = mutableListOf<String>()
        checkFile("case.kt", src, problems)
        assertTrue(
            "闸门漏判：应报「$needle」，实际=$problems",
            problems.any { it.contains(needle) },
        )
    }

    private fun expectClean(src: String) {
        val problems = mutableListOf<String>()
        checkFile("case.kt", src, problems)
        assertTrue("闸门误伤正常样本：$problems", problems.isEmpty())
    }

    internal fun checkFile(name: String, text: String, out: MutableList<String>) {
        val templateOpen = "\u0024{"
        // 模式栈：C=代码 L=行注释 B=块注释 S=普通串 R=原生串 H=字符
        val modes = ArrayDeque<Frame>()
        modes.addLast(Frame('C', 1))
        // 括号栈：kind 为 ( [ { T
        val brackets = ArrayDeque<Frame>()
        var line = 1
        var blockDepth = 0
        var i = 0

        fun err(msg: String) {
            out.add("$name:$line $msg")
        }

        while (i < text.length) {
            val c = text[i]
            if (c == '\n') line++
            when (modes.last().kind) {
                'L' -> {
                    if (c == '\n') modes.removeLast()
                    i++
                }
                'B' -> {
                    when {
                        text.startsWith("*/", i) -> {
                            blockDepth--
                            if (blockDepth == 0) modes.removeLast()
                            i += 2
                        }
                        text.startsWith("/*", i) -> {
                            blockDepth++
                            i += 2
                        }
                        else -> i++
                    }
                }
                'H' -> {
                    when {
                        c == '\\' -> i += 2
                        c == '\'' -> {
                            modes.removeLast()
                            i++
                        }
                        c == '\n' -> {
                            err("字符字面量没闭合就撞上换行（疑似转义吃引号）")
                            modes.removeLast()
                        }
                        else -> i++
                    }
                }
                'S' -> {
                    when {
                        c == '\\' -> i += 2
                        c == '"' -> {
                            modes.removeLast()
                            i++
                        }
                        text.startsWith(templateOpen, i) -> {
                            brackets.addLast(Frame('T', line))
                            modes.addLast(Frame('C', line))
                            i += 2
                        }
                        c == '\n' -> {
                            err("字符串没闭合就撞上换行（引号被吃的指纹）")
                            modes.removeLast()
                        }
                        else -> i++
                    }
                }
                'R' -> {
                    when {
                        text.startsWith("\"\"\"", i) -> {
                            modes.removeLast()
                            i += 3
                        }
                        text.startsWith(templateOpen, i) -> {
                            brackets.addLast(Frame('T', line))
                            modes.addLast(Frame('C', line))
                            i += 2
                        }
                        else -> i++
                    }
                }
                else -> {
                    // CODE
                    when {
                        text.startsWith("//", i) -> {
                            modes.addLast(Frame('L', line))
                            i += 2
                        }
                        text.startsWith("/*", i) -> {
                            modes.addLast(Frame('B', line))
                            blockDepth = 1
                            i += 2
                        }
                        text.startsWith("\"\"\"", i) -> {
                            modes.addLast(Frame('R', line))
                            i += 3
                        }
                        c == '"' -> {
                            modes.addLast(Frame('S', line))
                            i++
                        }
                        c == '\'' -> {
                            modes.addLast(Frame('H', line))
                            i++
                        }
                        c == '(' || c == '[' || c == '{' -> {
                            brackets.addLast(Frame(c, line))
                            i++
                        }
                        c == '}' && brackets.lastOrNull()?.kind == 'T' -> {
                            brackets.removeLast()
                            modes.removeLast()
                            i++
                        }
                        c == ')' || c == ']' || c == '}' -> {
                            val want = when (c) {
                                ')' -> '('
                                ']' -> '['
                                else -> '{'
                            }
                            val top = brackets.removeLastOrNull()
                            when {
                                top == null -> err("'$c' 找不到开括号")
                                top.kind != want -> err("'$c' 与第 ${top.line} 行的 '${top.kind}' 不配")
                            }
                            i++
                        }
                        else -> i++
                    }
                }
            }
        }

        if (modes.size > 1 || modes.last().kind != 'C') {
            val m = modes.last()
            out.add("$name:EOF 模式未闭合：'${m.kind}'（起于第 ${m.line} 行）")
        }
        for (b in brackets) {
            out.add("$name:EOF 括号未闭合：'${b.kind}'（起于第 ${b.line} 行）")
        }
        checkUnicodeScars(name, text, out)
    }

    /** 半个表情与替换符：v11f 烂字节同款指纹，编译器不拦，这里拦。 */
    private fun checkUnicodeScars(name: String, text: String, out: MutableList<String>) {
        var line = 1
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') line++
            when {
                c.isHighSurrogate() -> {
                    if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) {
                        out.add("$name:$line 孤立高代理（半个表情）")
                    } else {
                        i++
                    }
                }
                c.isLowSurrogate() -> out.add("$name:$line 孤立低代理（半个表情）")
                c == '\uFFFD' -> out.add("$name:$line 出现 U+FFFD 替换符（乱码字节疤）")
            }
            i++
        }
    }
}
