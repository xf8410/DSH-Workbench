package com.hualuo.engine.observe

import com.hualuo.engine.api.LineSink
import com.hualuo.engine.api.WireRequest
import com.hualuo.engine.api.WireResponse
import com.hualuo.engine.api.WireTransport
import com.hualuo.engine.toolcalls.ToolRegistry
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * uma_* 工具族测试：端点映射（含编码）、状态机把门、桥缺席报账——全离线。
 */
class UmaToolTest {

    /** 假桥：记录路径；可指定某路径的状态码。 */
    private class FakeBridge : WireTransport {
        val paths = mutableListOf<String>()
        var code = 200
        override fun exchange(request: WireRequest, sink: LineSink): WireResponse {
            val path = request.url.substringAfter("18765")
            paths += path
            val payload = """{"path":"$path","data":[1,2]}"""
            if (code in 200..299) sink.onLine(payload)
            return WireResponse(code, null, payload.length.toLong(), if (code in 200..299) null else "HTTP $code")
        }
        override fun cancel() {}
        override fun isCancelled(): Boolean = false
    }

    private fun registry(bridge: WireTransport?, state: ObserveState = ObserveState()): ToolRegistry {
        val r = ToolRegistry()
        UmaTool.register(r, { bridge?.let { ObserveClient("http://127.0.0.1:18765", it) } }, state)
        return r
    }

    private fun readyState(): ObserveState =
        ObserveState().apply { settleProbe(healthOk = true, statusOk = true, healthStatus = 200, note = "ok") }

    @Test
    fun `全族注册且桥在时可见`() {
        val r = registry(FakeBridge(), readyState())
        // 无参/可空参工具直接空参调用；带必填参的下面单独给参验证
        listOf(
            "uma_health", "uma_status", "uma_summary", "uma_event_choices",
            "uma_event_observations", "uma_hook_diagnostics", "uma_event_reward_targets",
            "uma_ramen_transitions", "uma_sniff_status", "uma_sniff_metadata",
            "uma_ramen", "uma_ramen_planner_state", "uma_seed_stats", "uma_carddb",
            "uma_mdb_schema", "uma_tables", "uma_gauge", "uma_training_seed",
        ).forEach { name -> assertTrue("$name 应可用", r.execute(name, "{}").ok) }
        assertTrue(r.execute("uma_search_classes", """{"keyword":"UmaClass"}""").ok)
        assertTrue(r.execute("uma_get_fields", """{"class_name":"UmaClass"}""").ok)
        assertTrue(r.execute("uma_get_methods", """{"class_name":"UmaClass"}""").ok)
        assertTrue(r.execute("uma_find_method", """{"method":"getHfState"}""").ok)
        // read_endpoint 的 path 必填，单独给参数验证
        assertTrue(r.execute("uma_read_endpoint", """{"path":"/debug/hookdiag"}""").ok)
    }

    @Test
    fun `桥缺席如实报账`() {
        val out = registry(null).execute("uma_health", "{}")
        assertTrue(!out.ok)
        // gated 语义：桥缺席=工具从清单消失，迟到调用按「此刻不可用」回话
        assertTrue(out.text.contains("不可用"))
    }

    @Test
    fun `health 端点映射与原文回传`() {
        val bridge = FakeBridge()
        val out = registry(bridge, readyState()).execute("uma_health", "{}")
        assertTrue(out.ok)
        assertEquals("/health", bridge.paths.single())
        assertTrue(out.text.contains("HTTP 200"))
        assertTrue(out.text.contains("/health"))
    }

    @Test
    fun `search_classes 关键词 URL 编码`() {
        val bridge = FakeBridge()
        registry(bridge, readyState()).execute("uma_search_classes", """{"keyword":"Uma/AI"}""")
        assertEquals("/classes/search/Uma%2FAI", bridge.paths.single())
    }

    @Test
    fun `observations 带 after_id 且负数归零`() {
        val bridge = FakeBridge()
        val r = registry(bridge, readyState())
        r.execute("uma_event_observations", """{"after_id":42}""")
        assertEquals("/api/event/observations?after_id=42", bridge.paths[0])
        r.execute("uma_event_observations", """{"after_id":-5}""")
        assertEquals("/api/event/observations?after_id=0", bridge.paths[1])
    }

    @Test
    fun `read_endpoint 缺路径报错`() {
        val out = registry(FakeBridge(), readyState()).execute("uma_read_endpoint", "{}")
        assertTrue(!out.ok)
        assertTrue(out.text.contains("path"))
    }

    @Test
    fun `状态机拒：DISCONNECTED 态工具被挡`() {
        val out = registry(FakeBridge(), ObserveState()).execute("uma_status", "{}")
        assertTrue(!out.ok)
        assertTrue(out.text.contains("探测"))
    }

    @Test
    fun `READY 态 HTTP 500 报账不炸`() {
        val bridge = FakeBridge().apply { code = 500 }
        val out = registry(bridge, readyState()).execute("uma_status", "{}")
        assertTrue(!out.ok)
        assertTrue(out.text.contains("500"))
    }

    @Test
    fun `单并发：busy 中再调被挡`() {
        val bridge = FakeBridge()
        val state = readyState()
        val r = registry(bridge, state)
        // 手占坑
        assertTrue(state.tryBeginCall())
        val out = r.execute("uma_status", "{}")
        assertTrue(!out.ok)
        assertTrue(out.text.contains("单并发") || out.text.contains("不让调"))
        state.settleCall(true, "放手")
    }


    @Test
    fun `报错带中文人话不甩裸码`() {
        val bridge = FakeBridge().apply { code = 404 }
        val out = registry(bridge, readyState()).execute("uma_status", "{}")
        assertTrue(!out.ok)
        assertTrue("应含中文解释：" + out.text, out.text.contains("没这条路由"))
        assertTrue("数字仍要留作事实", out.text.contains("404"))
    }

    @Test
    fun `连不上报人话指路探测`() {
        val broken = object : WireTransport {
            override fun exchange(request: WireRequest, sink: LineSink): WireResponse =
                throw java.io.IOException("Connection refused")
            override fun cancel() {}
            override fun isCancelled(): Boolean = false
        }
        val out = registry(broken, readyState()).execute("uma_health", "{}")
        assertTrue(!out.ok)
        assertTrue("应含中文解释：" + out.text, out.text.contains("连不上"))
        assertTrue("要给下一步指路", out.text.contains("探测"))
    }

    @Test
    fun `无参工具 schema 显式无需参数`() {
        val bridge = FakeBridge()
        val r = registry(bridge, readyState())
        val byName = r.specs().associateBy { it.name }
        listOf("uma_sniff_status", "uma_ramen", "uma_carddb").forEach { name ->
            val spec = byName[name]
            assertTrue("$name 应注册", spec != null)
            assertTrue("$name schema 应为空必填", spec!!.parametersJson.contains("\"required\":[]"))
            assertTrue("$name 不该有必填项", !spec.parametersJson.contains("\"required\":[\""))
        }
    }
}
