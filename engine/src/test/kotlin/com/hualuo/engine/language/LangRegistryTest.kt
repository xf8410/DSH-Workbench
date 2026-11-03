package com.hualuo.engine.language

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** 语言注册表测试：全量扩展名代表集 + 未知兜底（不省略任何格式的承诺要钉住）。 */
class LangRegistryTest {

    @Test
    fun `主流语言映射`() {
        assertEquals("Kotlin", LangRegistry.byExtension("kt").name)
        assertEquals("Kotlin", LangRegistry.byExtension("kts").name)
        assertEquals("Java", LangRegistry.byExtension("java").name)
        assertEquals("Python", LangRegistry.byExtension("py").name)
        assertEquals("Rust", LangRegistry.byExtension("rs").name)
        assertEquals("Go", LangRegistry.byExtension("go").name)
        assertEquals("C++", LangRegistry.byExtension("cpp").name)
        assertEquals("C#", LangRegistry.byExtension("cs").name)
        assertEquals("JavaScript", LangRegistry.byExtension("mjs").name)
        assertEquals("TypeScript", LangRegistry.byExtension("tsx").name)
        assertEquals("Swift", LangRegistry.byExtension("swift").name)
        assertEquals("Dart", LangRegistry.byExtension("dart").name)
        assertEquals("Shell", LangRegistry.byExtension("zsh").name)
        assertEquals("SQL", LangRegistry.byExtension("sql").name)
    }

    @Test
    fun `标记与数据格式`() {
        assertEquals("JSON", LangRegistry.byExtension("json").name)
        assertEquals("YAML", LangRegistry.byExtension("yml").name)
        assertEquals("TOML", LangRegistry.byExtension("toml").name)
        assertEquals("XML", LangRegistry.byExtension("xml").name)
        assertEquals("HTML", LangRegistry.byExtension("htm").name)
        assertEquals("SCSS/Sass", LangRegistry.byExtension("scss").name)
        assertEquals("Markdown", LangRegistry.byExtension("mdx").name)
        assertEquals("GraphQL", LangRegistry.byExtension("gql").name)
        assertEquals("Protobuf", LangRegistry.byExtension("proto").name)
    }

    @Test
    fun `冷门语言也在`() {
        assertEquals("COBOL", LangRegistry.byExtension("cbl").name)
        assertEquals("Fortran", LangRegistry.byExtension("f90").name)
        assertEquals("Zig", LangRegistry.byExtension("zig").name)
        assertEquals("Elixir", LangRegistry.byExtension("exs").name)
        assertEquals("Solidity", LangRegistry.byExtension("sol").name)
        assertEquals("CUDA", LangRegistry.byExtension("cu").name)
        assertEquals("Verilog/SystemVerilog", LangRegistry.byExtension("sv").name)
        assertEquals("PlantUML", LangRegistry.byExtension("puml").name)
        assertEquals("Mermaid 图", LangRegistry.byExtension("mmd").name)
        assertEquals("Jupyter 笔记本", LangRegistry.byExtension("ipynb").name)
    }

    @Test
    fun `未知扩展名落纯文本兜底——不省略任何格式`() {
        assertEquals("纯文本", LangRegistry.byExtension("xyz123").name)
        assertEquals("纯文本", LangRegistry.byExtension("").name)
        assertEquals("纯文本", LangRegistry.byExtension("weirdextension").name)
    }

    @Test
    fun `大小写与点号前缀都吃`() {
        assertEquals("Kotlin", LangRegistry.byExtension(".KT").name)
        assertEquals("Rust", LangRegistry.byExtension("  RS  ").name)
    }

    @Test
    fun `无扩展名文件名映射`() {
        assertEquals("Dockerfile", LangRegistry.byFileName("Dockerfile").name)
        assertEquals("Makefile", LangRegistry.byFileName("Makefile").name)
        assertEquals("Groovy/Jenkinsfile", LangRegistry.byFileName("Jenkinsfile").name)
        assertEquals("Markdown", LangRegistry.byFileName("README").name)
        assertEquals("纯文本", LangRegistry.byFileName("LICENSE").name)
        assertEquals("JSON", LangRegistry.byFileName("package.json").name)
    }

    @Test
    fun `表规模钉住：映射不能悄悄缩水`() {
        assertTrue("扩展名映射应不少于 200 条（现在是 ${LangRegistry.extCount()}）", LangRegistry.extCount() >= 200)
        assertTrue("语言注册应不少于 60 种", LangRegistry.allLangs().size >= 60)
    }
}
