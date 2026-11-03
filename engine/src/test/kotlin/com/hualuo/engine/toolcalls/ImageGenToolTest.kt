package com.hualuo.engine.toolcalls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * 图像生成工具对照表：全离线（poster/downloader/persist 全注入假件）。
 */
class ImageGenToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    private fun config(key: String = "sk-test") = ImageGenConfig(
        apiKey = key,
        baseUrl = "https://api.example.com/v1",
        model = "gpt-image-1",
        size = "1024x1024",
    )

    /** 注册并收集 persist 收到的字节；返回 (registry, savedPaths, capturedBytes)。 */
    private fun registry(
        cfg: ImageGenConfig?,
        poster: (String, String, String) -> String,
        downloader: (String) -> ByteArray = { pngBytes },
    ): Triple<ToolRegistry, MutableList<String>, MutableList<ByteArray>> {
        val saved = mutableListOf<String>()
        val captured = mutableListOf<ByteArray>()
        val r = ToolRegistry()
        ImageGenTool.register(
            r,
            loadConfig = { cfg },
            poster = poster,
            downloadBytes = downloader,
            persist = { bytes, prefix ->
                captured.add(bytes)
                val f = File(tmp.root, "$prefix-${saved.size + 1}.png")
                f.writeBytes(bytes)
                saved.add(f.absolutePath)
                f.absolutePath
            },
        )
        return Triple(r, saved, captured)
    }

    @Test
    fun noKeyMeansInvisible() {
        val (r1, _, _) = registry(null, poster = { _, _, _ -> "{}" })
        assertTrue("没配置=不可见：", r1.specs().isEmpty())
        val (r2, _, _) = registry(config(key = "  "), poster = { _, _, _ -> "{}" })
        assertTrue("空钥匙=不可见：", r2.specs().isEmpty())
        val (r3, _, _) = registry(config(), poster = { _, _, _ -> "{}" })
        assertEquals("有钥匙才出现：", listOf("generate_image"), r3.specs().map { it.name })
    }

    @Test
    fun b64PathDecodesAndPersists() {
        val b64 = Base64.getEncoder().encodeToString(pngBytes)
        var postedUrl = ""
        var postedBody = ""
        val (r, saved, captured) = registry(config(), poster = { url, body, _ ->
            postedUrl = url; postedBody = body
            """{"data":[{"b64_json":"$b64"}]}"""
        })
        val out = r.execute("generate_image", """{"prompt":"一只猫"}""")
        assertTrue(out.ok)
        assertEquals("端点拼对：", "https://api.example.com/v1/images/generations", postedUrl)
        assertTrue("prompt 进请求体：", postedBody.contains("一只猫"))
        assertTrue("尺寸进请求体：", postedBody.contains("1024x1024"))
        assertTrue("字节落地：", captured.first().contentEquals(pngBytes))
        assertTrue("回执带路径：${out.text}", out.text.contains("saved"))
        assertTrue("回执带字节数：", out.text.contains("\"bytes\":${pngBytes.size}"))
        assertTrue("落盘真实可读：", File(saved.first()).readBytes().contentEquals(pngBytes))
    }

    @Test
    fun urlPathDownloadsBytes() {
        val (r, saved, captured) = registry(
            config(),
            poster = { _, _, _ -> """{"data":[{"url":"https://cdn.example.com/img.png"}]}""" },
            downloader = { pngBytes },
        )
        val out = r.execute("generate_image", """{"prompt":"猫","size":"512x512"}""")
        assertTrue(out.ok)
        assertTrue(captured.first().contentEquals(pngBytes))
        assertTrue("尺寸用参数的：", out.text.contains("512x512"))
        assertTrue(saved.first().isNotBlank())
    }

    @Test
    fun errorPathsSpeakCleanly() {
        // 缺 prompt
        val (r1, _, _) = registry(config(), poster = { _, _, _ -> "{}" })
        val e1 = r1.execute("generate_image", "{}")
        assertTrue("缺 prompt 报 no_prompt：", e1.ok && e1.text.contains("no_prompt"))

        // poster 抛异常 -> generation_error
        val (r2, _, _) = registry(config(), poster = { _, _, _ -> throw java.io.IOException("连不上") })
        val e2 = r2.execute("generate_image", """{"prompt":"猫"}""")
        assertTrue("网络炸报 generation_error：", e2.ok && e2.text.contains("generation_error"))
        assertTrue("带原因：", e2.text.contains("连不上"))

        // 回执没 data -> no_image
        val (r3, _, _) = registry(config(), poster = { _, _, _ -> """{"error":{"message":"bad key"}}""" })
        val e3 = r3.execute("generate_image", """{"prompt":"猫"}""")
        assertTrue("无图报 no_image：", e3.ok && e3.text.contains("no_image"))

        // url 下载炸 -> download_failed
        val (r4, _, _) = registry(
            config(),
            poster = { _, _, _ -> """{"data":[{"url":"https://cdn.example.com/x.png"}]}""" },
            downloader = { throw java.io.IOException("404") },
        )
        val e4 = r4.execute("generate_image", """{"prompt":"猫"}""")
        assertTrue("下载炸报 download_failed：", e4.ok && e4.text.contains("download_failed"))

        // 回执不是 JSON -> no_image
        val (r5, _, _) = registry(config(), poster = { _, _, _ -> "<html>网关错误</html>" })
        val e5 = r5.execute("generate_image", """{"prompt":"猫"}""")
        assertTrue("非 JSON 报 no_image：", e5.ok && e5.text.contains("no_image"))
    }

    @Test
    fun gateFlipsLiveWithConfig() {
        var key: String? = null
        val r = ToolRegistry()
        ImageGenTool.register(
            r,
            loadConfig = { key?.let { ImageGenConfig(it, "https://x/v1", "m", "1024x1024") } },
            poster = { _, _, _ -> """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(pngBytes)}"}]}""" },
            downloadBytes = { pngBytes },
            persist = { bytes, prefix -> File(tmp.root, "$prefix.bin").also { it.writeBytes(bytes) }.absolutePath },
        )
        assertTrue("没钥匙不可见：", r.specs().isEmpty())
        key = "sk-live"
        assertTrue("配了立刻出现（每轮现问）：", r.specs().map { it.name } == listOf("generate_image"))
        key = ""
        assertFalse("钥匙清掉立刻消失：", r.specs().isNotEmpty())
    }
}
