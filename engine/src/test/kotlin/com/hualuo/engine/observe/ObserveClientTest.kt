package com.hualuo.engine.observe

import com.hualuo.engine.api.WireRequest
import com.hualuo.engine.api.WireResponse
import com.hualuo.engine.api.WireTransport
import com.hualuo.engine.api.LineSink
import java.io.IOException
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows

/**
 * 观测桥客户端测试：路径管束、字节上限、探测六态判定——全部离线假 transport。
 */
class ObserveClientTest {

    /** 假桥：按脚本回；记录收到的路径与超时档。 */
    private class FakeBridge(
        var healthBody: String? = """{"version":"v3.27.11","ok":true}""",
        var healthCode: Int = 200,
        var statusBody: String? = """{"state":"ready"}""",
        var statusCode: Int = 200,
        var throwIo: Boolean = false,
        var readTimeoutFloor: Int = 0,
    ) : WireTransport {
        val paths = mutableListOf<String>()
        val timeouts = mutableListOf<Int>()
        override fun exchange(request: WireRequest, sink: LineSink): WireResponse {
            val path = request.url.substringAfter("18765")
            paths += path
            timeouts += request.readTimeoutMs
            if (throwIo) throw IOException("连接被拒")
            val (code, body) = when {
                path.startsWith("/health") -> healthCode to healthBody
                path.startsWith("/status") -> statusCode to statusBody
                else -> 200 to """{"echo":"$path"}"""
            }
            val payload = body ?: """{"err":"no body"}"""
            if (code in 200..299) sink.onLine(payload)
            return WireResponse(code, null, payload.length.toLong(), if (code in 200..299) null else "HTTP $code")
        }
        override fun cancel() {}
        override fun isCancelled(): Boolean = false
    }

    private fun client(bridge: FakeBridge) = ObserveClient("http://127.0.0.1:18765", bridge)

    @Test
    fun `get 拼 2xx 原文并带事实账`() {
        val out = client(FakeBridge()).get("/status")
        assertTrue(out.ok)
        assertEquals("""{"state":"ready"}""", out.body!!.trim())
        assertEquals(200, out.httpStatus)
        assertTrue(out.bytes > 0)
    }

    @Test
    fun `非 2xx 报错误体不炸`() {
        val out = client(FakeBridge(statusCode = 404, statusBody = """{"err":"not found"}""")).get("/status")
        assertTrue(!out.ok)
        assertEquals(404, out.httpStatus)
        assertTrue(out.error!!.contains("404"))
    }

    @Test
    fun `连接失败转成账`() {
        val out = client(FakeBridge(throwIo = true)).get("/status")
        assertTrue(!out.ok)
        assertEquals(null, out.httpStatus)
        assertTrue(out.error!!.isNotBlank())
    }

    @Test
    fun `超限断流报账不炸`() {
        val big = """{"blob":"${"x".repeat(200_000)}"}"""
        val out = client(FakeBridge(statusBody = big)).get("/status", maxBytes = 64 * 1024)
        assertTrue(!out.ok)
        assertTrue(out.error!!.contains("上限"))
    }

    @Test
    fun `路径校验拒绝注入与坏形状`() {
        assertThrows(IllegalArgumentException::class.java) { ObserveClient.validateReadPath("http://evil") }
        assertThrows(IllegalArgumentException::class.java) { ObserveClient.validateReadPath("//evil") }
        assertThrows(IllegalArgumentException::class.java) { ObserveClient.validateReadPath("/a\u0000b") }
        assertThrows(IllegalArgumentException::class.java) { ObserveClient.validateReadPath("/a\nb") }
        assertThrows(IllegalArgumentException::class.java) { ObserveClient.validateReadPath("/") }
        assertThrows(IllegalArgumentException::class.java) { ObserveClient.validateReadPath("/" + "x".repeat(1001)) }
        assertEquals("/ok", ObserveClient.validateReadPath("/ok"))
    }

    @Test
    fun `safeSegment URL 编码防斜杠注入`() {
        assertEquals("a%2Fb", ObserveClient.safeSegment("a/b", "kw"))
        assertEquals("%E3%81%82", ObserveClient.safeSegment("あ", "kw"))
        assertThrows(IllegalArgumentException::class.java) { ObserveClient.safeSegment("", "kw") }
        assertThrows(IllegalArgumentException::class.java) { ObserveClient.safeSegment("x".repeat(501), "kw") }
    }

    @Test
    fun `读超时按端点分档`() {
        assertEquals(10_000, ObserveClient.readTimeoutFor("/health"))
        assertEquals(10_000, ObserveClient.readTimeoutFor("/status"))
        assertEquals(60_000, ObserveClient.readTimeoutFor("/summary"))
        assertEquals(30_000, ObserveClient.readTimeoutFor("/debug/hookdiag"))
    }

    @Test
    fun `probe 全通 = READY 序列`() {
        val bridge = FakeBridge()
        val p = client(bridge).probe()
        assertTrue(p.health!!.ok)
        assertTrue(p.status!!.ok)
        assertEquals(listOf("/health", "/status"), bridge.paths)
    }

    @Test
    fun `probe health 败只发 health`() {
        val bridge = FakeBridge(healthCode = 500)
        val p = client(bridge).probe()
        assertTrue(!p.health!!.ok)
        assertEquals(null, p.status)
        assertEquals(listOf("/health"), bridge.paths)
    }

    @Test
    fun `probe health 通 status 败 = 降级素材`() {
        val bridge = FakeBridge(statusCode = 500)
        val p = client(bridge).probe()
        assertTrue(p.health!!.ok)
        assertTrue(!p.status!!.ok)
    }

    @Test
    fun `probe 非 JSON 原文 = 疑似非 hlpatch 素材`() {
        val bridge = FakeBridge(healthBody = "<html>hello</html>")
        val p = client(bridge).probe()
        assertTrue(p.health!!.ok)
        assertEquals(null, p.status)
    }
}
