package com.hualuo.engine.toolcalls

import com.hualuo.engine.github.GitHubCommitWritten
import com.hualuo.engine.github.GitHubRepoClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 传文件工具的纯 JVM 契约：本地路径到仓库路径的映射、前缀拼接、逐文件报账
 * （成功带 commit、失败带人话）、空文件/不存在的文件在碰网前拦下、缺令牌不碰网。
 * 上传动作注入假实现，绝不碰真网。
 */
class GitHubUploadToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeUpload : GitHubFileUpload {
        val repos = ArrayList<String>()
        val paths = ArrayList<String>()
        val sizes = ArrayList<Long>()
        var next = GitHubCommitWritten("c0ffee1234", "tree", null)

        override fun upload(
            repo: String,
            path: String,
            branch: String?,
            message: String,
            source: GitHubRepoClient.UploadSource,
            token: String?,
        ): GitHubCommitWritten {
            repos += repo
            paths += path
            sizes += source.sizeBytes
            return next
        }
    }

    private fun registryOf(
        upload: GitHubFileUpload,
        token: String? = "tok",
        repo: String? = "o/r",
    ): ToolRegistry {
        val registry = ToolRegistry()
        GitHubUploadTool.register(registry, { token }, { repo }, upload)
        return registry
    }

    @Test
    fun uploadsEveryFileUnderThePrefix() {
        val apk = tmp.newFile("app-debug.apk").apply { writeText("apk-bytes") }
        val img = tmp.newFile("shot.png").apply { writeText("png-bytes") }
        val upload = FakeUpload()
        val outcome = registryOf(upload).execute(
            "github_upload_files",
            """{"files":["${apk.absolutePath}","${img.absolutePath}"],"prefix":"courier/"}""",
        )

        assertTrue("传文件应成功：${outcome.text}", outcome.ok)
        assertEquals(listOf("courier/app-debug.apk", "courier/shot.png"), upload.paths)
        assertTrue("结果要带成功计数：${outcome.text}", outcome.text.contains("成功 2 个"))
        assertTrue("结果要带 commit：${outcome.text}", outcome.text.contains("c0ffee12"))
    }

    @Test
    fun missingFileIsReportedNotSilentlyDropped() {
        val upload = FakeUpload()
        val outcome = registryOf(upload).execute(
            "github_upload_files",
            """{"files":["/sdcard/definitely-not-here-9f3a.apk"]}""",
        )

        assertTrue("整通调用不该抛穿：${outcome.text}", outcome.ok)
        assertTrue("失败要逐条点名：${outcome.text}", outcome.text.contains("失败——找不到"))
        assertTrue("结果要带失败计数：${outcome.text}", outcome.text.contains("失败 1 个"))
        assertEquals("不存在的文件不许碰上传", 0, upload.paths.size)
    }

    @Test
    fun emptyFileIsRefusedBeforeUpload() {
        val empty = tmp.newFile("empty.apk")
        val upload = FakeUpload()
        val outcome = registryOf(upload).execute("github_upload_files", """{"files":["${empty.absolutePath}"]}""")

        assertTrue(outcome.ok)
        assertTrue("空文件要说明：${outcome.text}", outcome.text.contains("空文件"))
        assertEquals(0, upload.paths.size)
    }

    @Test
    fun missingTokenStaysOffline() {
        val apk = tmp.newFile("x.apk").apply { writeText("x") }
        val upload = FakeUpload()
        val outcome = registryOf(upload, token = null).execute("github_upload_files", """{"files":["${apk.absolutePath}"]}""")

        assertFalse(outcome.ok)
        assertTrue("要指出令牌的出路：${outcome.text}", outcome.text.contains("令牌"))
        assertEquals(0, upload.paths.size)
    }

    @Test
    fun repoUrlIsNormalizedAndUploadErrorsArePassedThrough() {
        val apk = tmp.newFile("y.apk").apply { writeText("y") }
        val upload = FakeUpload().apply { next = GitHubCommitWritten(null, null, "blob 上传失败（网络中断或对端拒收）") }
        val outcome = registryOf(upload).execute(
            "github_upload_files",
            """{"files":["${apk.absolutePath}"],"repo":"https://github.com/o/r"}""",
        )

        assertEquals("链接要先归一化成 owner/name", listOf("o/r"), upload.repos)
        assertTrue("上传失败的人话要透传：${outcome.text}", outcome.text.contains("blob 上传失败"))
        assertTrue("失败计数要出声：${outcome.text}", outcome.text.contains("失败 1 个"))
    }
}