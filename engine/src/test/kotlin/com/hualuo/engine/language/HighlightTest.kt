package com.hualuo.engine.language

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** 染色器测试：注释/字符串/数字/关键词四色 + 跨行块注释状态机。 */
class HighlightTest {

    private fun kindsOf(line: Highlight.LineSpans): List<Highlight.Kind> =
        line.spans.map { it.kind }

    @Test
    fun `行注释整行灰`() {
        val lang = LangRegistry.byExtension("kt")
        val out = Highlight.highlight("// 全是注释", lang)
        assertEquals(listOf(Highlight.Kind.COMMENT), kindsOf(out[0]))
    }

    @Test
    fun `行内注释后半段灰`() {
        val lang = LangRegistry.byExtension("kt")
        val out = Highlight.highlight("val x = 1 // 尾注", lang)
        val ks = kindsOf(out[0])
        assertTrue(ks.contains(Highlight.Kind.COMMENT))
        assertEquals(Highlight.Kind.COMMENT, ks.last())
    }

    @Test
    fun `字符串整段`() {
        val lang = LangRegistry.byExtension("python")
        val out = Highlight.highlight("s = \"hello world\"", lang)
        val ks = kindsOf(out[0])
        assertTrue(ks.contains(Highlight.Kind.STRING))
    }

    @Test
    fun `数字与关键词`() {
        val lang = LangRegistry.byExtension("kt")
        val out = Highlight.highlight("val count = 42", lang)
        val ks = kindsOf(out[0])
        assertTrue(ks.contains(Highlight.Kind.KEYWORD))
        assertTrue(ks.contains(Highlight.Kind.NUMBER))
    }

    @Test
    fun `块注释跨行状态机`() {
        val lang = LangRegistry.byExtension("java")
        val out = Highlight.highlight("a /* 开始\n还在注释\n结束 */ b", lang)
        assertEquals(listOf(Highlight.Kind.COMMENT), kindsOf(out[1]))
        val last = out[2]
        assertTrue(last.spans.first().kind == Highlight.Kind.COMMENT)
        assertTrue(last.spans.last().kind == Highlight.Kind.PLAIN)
    }

    @Test
    fun `python 单双引号不炸就行`() {
        val lang = LangRegistry.byExtension("py")
        val out = Highlight.highlight("x = \"abc\" + 'def'", lang)
        assertTrue(out[0].spans.isNotEmpty())
    }

    @Test
    fun `未知语言退化为注释与字符串也能看`() {
        val lang = LangRegistry.PLAIN
        val out = Highlight.highlight("# 注释\n\"字符串\"", lang)
        assertEquals(listOf(Highlight.Kind.COMMENT), kindsOf(out[0]))
        assertEquals(listOf(Highlight.Kind.STRING), kindsOf(out[1]))
    }
}
