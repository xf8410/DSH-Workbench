package com.hualuo.engine.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 明文硬守门（沙盒包专用）：数据必须全程明文流转，源码里不许出现任何
 * 「值替换 / 内容隐藏 / 身份抹除」形态的明文卫生违例。
 *
 * 这是加给 sandbox 包的新守门（不改动既有 SourceHygieneTest / NoEmojiInSourceTest）。
 * 规则：
 *  1. 源码里禁出现明文卫生禁词族（本文件注释与样本一律用拼接构造，守门不咬自己）；
 *  2. 输出组装禁「静默替换值」模式：sandbox 包不许用 replace 把真实值换成星号占位再返回；
 *  3. detectorBitesOnKnownBadSamples 是变异自测——守门若不会咬已知坏样本，它对全包的
 *     「通过」一文不值（对齐 SourceHygieneTest 的配套纪律）。
 */
class SandboxPlaintextGateTest {

    // 禁词表用拼接构造：测试文件自己也不许出现这些完整词
    private val banned = listOf(
        "red" + "act", "REDA" + "CT", "Red" + "act",
        "sanit" + "ize", "Sanit" + "ize", "SANIT" + "IZE",
        "ma" + "sk", "Ma" + "sk", "MA" + "SK",
        "anonym" + "iz", "Anonym" + "iz", "ANONYM" + "IZ",
        "打" + "码", "遮" + "盖", "掩" + "码", "脱" + "敏",
    )

    private fun sources(): List<Pair<String, String>> {
        val roots = listOf(
            File("..", "engine/src/main/kotlin/com/hualuo/engine/sandbox"),
            File("..", "engine/src/test/kotlin/com/hualuo/engine/sandbox"),
        )
        val out = ArrayList<Pair<String, String>>()
        for (root in roots) {
            assertTrue("找不到源码目录：$root", root.isDirectory)
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                out.add(f.path.substringAfter("engine/src/") to f.readText())
            }
        }
        assertTrue("沙盒包源码扫描数异常（${out.size} 个文件，少于 8 说明目录错了）", out.size >= 8)
        return out
    }

    @Test
    fun sandboxSourcesContainNoPlaintextHygieneWords() {
        val problems = mutableListOf<String>()
        for ((name, text) in sources()) {
            text.lineSequence().forEachIndexed { idx, line ->
                for (w in banned) {
                    if (w in line) problems.add("$name:${idx + 1} 含「$w」")
                }
            }
        }
        assertEquals("沙盒包必须零明文卫生违例词：\n${problems.joinToString("\n")}", emptyList<String>(), problems)
    }

    @Test
    fun sandboxSourcesDoNotReplaceValuesWithPlaceholders() {
        // 禁「值替换」形态：星号占位是典型的值替换产物
        val patterns = listOf("replace(\"***", "replace('" + "***", "[hid" + "den]", "[RED" + "ACTED]")
        val problems = mutableListOf<String>()
        for ((name, text) in sources()) {
            for (p in patterns) {
                if (p in text) problems.add("$name 含占位符模式「$p」")
            }
        }
        assertEquals("沙盒包不许有占位符替换：\n${problems.joinToString("\n")}", emptyList<String>(), problems)
    }

    @Test
    fun detectorBitesOnKnownBadSamples() {
        // 变异自测：守门必须会咬这些已知坏样本，否则本测试形同虚设
        val badSamples = listOf(
            "fun x() { return red" + "act(input) }",
            "val s = sanit" + "izeForLog(v)",
            "return ma" + "skSecrets(text)",
            "输出已打" + "码处理",
            "map = MA" + "SK_ALL",
        )
        for (sample in badSamples) {
            val hit = banned.any { it in sample } || "[RED" + "ACTED]" in sample
            assertTrue("守门必须咬住坏样本：$sample", hit)
        }
    }
}
