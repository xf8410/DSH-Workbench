package com.hualuo.engine.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorPolicyTest {

    @Test
    fun defaultHasOfficialFirstThenChinaMirrors() {
        val m = MirrorPolicy()
        assertEquals("官方应排第一（版本最正）：${m.repositories}", "https://dl-cdn.alpinelinux.org/alpine/v3.21/main", m.current())
        assertTrue("至少三家（官方+清华+中科大）：${m.repositories}", m.repositories.size >= 3)
    }

    @Test
    fun failoverMovesFailedMirrorToTail() {
        val m = MirrorPolicy()
        val official = m.current()
        val after = m.next(official)
        assertTrue("失败的那家要挪到队尾：before=${m.repositories} after=${after.repositories}", after.current() != official)
        assertEquals("队尾保留原列表（不丢源）：", official, after.repositories.last())
    }

    @Test
    fun singleMirrorFailoverIsSelf() {
        val m = MirrorPolicy(listOf("https://only-one.example/alpine"), "nameserver 1.1.1.1\n")
        assertTrue("只有一家时 next 返回自身：", m.next("https://only-one.example/alpine") === m)
    }

    @Test
    fun emptyMirrorListIsRejectedAtConstruction() {
        val err = runCatching { MirrorPolicy(emptyList(), "x") }.exceptionOrNull()
        assertTrue("空镜像表必须在构造时炸，不许等到下载期", err is IllegalArgumentException)
    }

    @Test
    fun pathBuildersJoinCorrectly() {
        val m = MirrorPolicy()
        assertEquals(
            "APKINDEX 路径拼接：",
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/main/APKINDEX.tar.gz",
            m.apkIndexPathOn(m.current()),
        )
        assertTrue("rootfs 路径含 aarch64 与 minirootfs：${m.rootfsUrlOn(m.current())}", m.rootfsUrlOn(m.current()).contains("aarch64") && m.rootfsUrlOn(m.current()).contains("minirootfs"))
    }
}
