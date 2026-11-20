package com.hualuo.engine.toolcalls

import com.hualuo.engine.github.GitHubHttpResult
import com.hualuo.engine.github.GitHubRepoClient
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 新工具（http_head / http_download / apk_inspect / github_edit_file / github_pr_comment /
 * github_workflow_list）的离线契约：非法目标在网络之前就被挡、注册面数量对得上、
 * 编辑锚点的三种收场（找不到 / 重复 / 正常）都说得清。
 */
class NewToolsTest {

    private fun httpRegistry(): ToolRegistry = ToolRegistry().also { HttpFetchTool.register(it) }

    private fun apkRegistry(): ToolRegistry = ToolRegistry().also { ApkTool.register(it) }

    @Test
    fun httpToolsAreRegistered() {
        assertEquals(setOf("http_head", "http_download"), httpRegistry().specs().map { it.name }.toSet())
    }

    @Test
    fun downloadRejectsNonHttpSchemeBeforeAnyNetwork() {
        val outcome = httpRegistry().execute(
            "http_download",
            """{"url":"file:///etc/passwd","path":"/sdcard/Download/x.txt"}""",
        )
        assertFalse(outcome.ok)
        assertTrue(outcome.text.contains("http"))
    }

    @Test
    fun downloadRejectsRelativeOrDotDotPathBeforeAnyNetwork() {
        val relative = httpRegistry().execute(
            "http_download",
            """{"url":"https://example.com/a.zip","path":"Download/a.zip"}""",
        )
        assertFalse(relative.ok)
        assertTrue(relative.text.contains("绝对路径"))

        val dotdot = httpRegistry().execute(
            "http_download",
            """{"url":"https://example.com/a.zip","path":"/sdcard/../data/x.zip"}""",
        )
        assertFalse(dotdot.ok)
        assertTrue(dotdot.text.contains(".."))
    }

    @Test
    fun apkInspectIsRegisteredAndExplainsMissingFile() {
        assertEquals(setOf("apk_inspect"), apkRegistry().specs().map { it.name }.toSet())
        val outcome = apkRegistry().execute("apk_inspect", """{"path":"/sdcard/Download/nope.apk"}""")
        assertFalse(outcome.ok)
        assertTrue(outcome.text.contains("找不到"))
    }

    @Test
    fun apkInspectRejectsNonZipFile() {
        val tmp = File.createTempFile("not-an-apk", ".apk")
        tmp.writeText("这不是 zip")
        val outcome = apkRegistry().execute("apk_inspect", """{"path":"${tmp.path}"}""")
        tmp.delete()
        assertFalse(outcome.ok)
        assertTrue(outcome.text, outcome.text.contains("不是 APK"))
    }

    @Test
    fun editFileSaysWhenAnchorIsMissing() {
        val registry = writeRegistry(anchorText = "alpha\nbeta\n")
        val outcome = registry.execute(
            "github_edit_file",
            """{"repo":"o/r","path":"A.kt","old":"gamma","message":"改一句话"}""",
        )
        assertFalse(outcome.ok)
        assertTrue(outcome.text.contains("原文没找到"))
    }

    @Test
    fun editFileRefusesAmbiguousAnchorWithoutReplaceAll() {
        val registry = writeRegistry(anchorText = "x\nx\n")
        val outcome = registry.execute(
            "github_edit_file",
            """{"repo":"o/r","path":"A.kt","old":"x","message":"改"}""",
        )
        assertFalse(outcome.ok)
        assertTrue(outcome.text.contains("出现"))
    }

    @Test
    fun editFileWritesReplacedContent() {
        var written = ""
        var seenSha = ""
        val registry = writeRegistry(anchorText = "alpha\nbeta\n", onWrite = { text, sha -> written = text; seenSha = sha })
        val outcome = registry.execute(
            "github_edit_file",
            """{"repo":"o/r","path":"A.kt","old":"beta","new":"gamma","message":"改 beta 为 gamma"}""",
        )
        assertTrue(outcome.text, outcome.ok)
        assertEquals("alpha\ngamma\n", written)
        assertEquals("sha-1", seenSha)
    }

    private fun writeRegistry(
        anchorText: String,
        onWrite: (String, String) -> Unit = { _, _ -> },
    ): ToolRegistry {
        val repoClient = GitHubRepoClient(
            fetch = { url, _, _, _ ->
                if (url.contains("/contents/")) {
                    GitHubHttpResult(
                        200,
                        """{"type":"file","path":"A.kt","sha":"sha-1","encoding":"base64",""" +
                            """"content":"${java.util.Base64.getEncoder().encodeToString(anchorText.toByteArray())}",""" +
                            """"size":${anchorText.length}}""",
                    )
                } else {
                    GitHubHttpResult(404, """{"message":"nope"}""")
                }
            },
            putJson = { _, _, body ->
                val idx = body.indexOf("\"content\":\"")
                val b64 = body.substring(idx + 11).substringBefore('"')
                val content = String(java.util.Base64.getDecoder().decode(b64))
                onWrite(content, "sha-1")
                GitHubHttpResult(200, """{"content":{"sha":"new-blob"},"commit":{"sha":"deadbeefcafe1234"}}""")
            },
        )
        return ToolRegistry().also {
            GitHubExtraWriteTool.register(
                it,
                { "tok" },
                { "o/r" },
                ActionConfirmer { true },
                repoClient = repoClient,
            )
        }
    }
}
