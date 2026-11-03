package com.hualuo.engine.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * APKINDEX 解析与 D1 统一口径的对照表测试。
 * 每个输入打印期望与实际（契约二.8：不许靠反复推理猜）。
 */
class AlpineRepoIndexTest {

    private val indexText = """
        C:Q1aaa
        P:musl
        V:1.2.5-r0
        o:musl
        D:so:libc.so
        p:so:libc.so

        C:Q2bbb
        P:python3
        V:3.12.0-r0
        o:python3
        D:musl so:libcrypto.so.3

        C:Q3ccc
        P:py3-requests
        V:2.31.0-r1
        o:py3-requests
        D:python3

        C:Q4ddd
        P:busybox
        V:1.36.1-r5
        o:busybox
        p:/bin/sh=0.0.0-r0 so:libc.so
    """.trimIndent()

    private fun idx() = AlpineRepoIndex.parse(indexText)

    @Test
    fun parseCountsAndLookupTable() {
        val idx = idx()
        assertEquals("索引包数应=4：$indexText", 4, idx.pkgCount)
        val table = listOf("musl" to "1.2.5-r0", "python3" to "3.12.0-r0", "py3-requests" to "2.31.0-r1", "busybox" to "1.36.1-r5")
        for ((name, ver) in table) {
            val got = idx.resolve(name)?.version
            assertEquals("对照表 name=$name 期望版本=$ver 实际=$got", ver, got)
        }
    }

    @Test
    fun aliasGoesThroughSameResolveAsTopLevel() {
        val idx = idx()
        // /bin/sh 是 busybox 的 Provides 别名——旧实现顶层查主表直接判死，D1 修复后应命中
        val viaAlias = idx.resolve("/bin/sh")
        assertEquals("别名 /bin/sh 应落到 busybox，实际=${viaAlias}", "busybox", viaAlias?.name)
        // so: 前缀别名同样可查
        assertEquals("别名 so:libc.so 应落到 musl（两个提供者取先建表者）", "musl", idx.resolve("so:libc.so")?.name)
    }

    @Test
    fun missingNameReturnsNull() {
        assertNull("不存在的包应 null，实际=${idx().resolve("no-such-pkg")}", idx().resolve("no-such-pkg"))
    }

    @Test
    fun dependsAndProvidesParsed() {
        val busy = idx().resolve("busybox")
        assertNotNull(busy)
        assertEquals("busybox 应有 1 条 provides，实际=${busy!!.provides}", listOf("/bin/sh=0.0.0-r0", "so:libc.so"), busy.provides)
        assertEquals("python3 依赖应 2 条，实际=${idx().resolve("python3")!!.depends}", listOf("musl", "so:libcrypto.so.3"), idx().resolve("python3")!!.depends)
    }

    @Test
    fun malformedLinesDoNotKillParsing() {
        val broken = "P:aaa\nXX坏行\nV:1.0-r0\n\nP:bbb\nV:2.0-r0\n"
        val idx = AlpineRepoIndex.parse(broken)
        assertEquals("坏行不许让索引报废：a=1 b=2，实际=${idx.pkgCount}", 2, idx.pkgCount)
        assertEquals("aaa", idx.resolve("aaa")?.name)
    }

    @Test
    fun emptyIndexIsOkayButResolvesNothing() {
        val idx = AlpineRepoIndex.parse("")
        assertEquals(0, idx.pkgCount)
        assertNull(idx.resolve("musl"))
    }
}
