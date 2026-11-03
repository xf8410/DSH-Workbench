package com.hualuo.engine.toolcalls

import com.hualuo.engine.api.LineSink
import com.hualuo.engine.api.ProviderProfile
import com.hualuo.engine.api.ProviderProtocol
import com.hualuo.engine.api.ProviderSession
import com.hualuo.engine.api.WireRequest
import com.hualuo.engine.api.WireResponse
import com.hualuo.engine.api.WireTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * 看视频工具族对照表：全离线（假 transport 喂脚本、临时目录当帧缓存）。
 * 核心语义：主对话模型调 watch_video 拿到的是**文字**——眼睛模型是谁它不用管。
 */
class VideoToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private fun session() = ProviderSession(
        ProviderProfile("测试", "https://api.example.com/v1", "sk-eye", "gemini-eye"),
        ProviderProtocol.OPENAI_COMPAT,
    )

    /** 假眼睛：每批回答一段描述，汇总请求回汇总文；记录收到的 image 数。 */
    private class FakeEye : WireTransport {
        var imagesSeen = 0
        var failBatch1 = false
        var failSummary = false
        var lastRequest: WireRequest? = null
        override fun exchange(request: WireRequest, sink: LineSink): WireResponse {
            lastRequest = request
            val body = request.body.orEmpty()
            val isVision = body.contains("image_url")
            if (isVision) imagesSeen += body.split("data:image/jpeg;base64,").size - 1
            val text = when {
                isVision && failBatch1 -> null
                isVision -> "第${body.hashCode() % 100}批：画面照实读"
                failSummary -> null
                else -> "【画面流水】看完\n【攻略要点】三条"
            }
            val code = if (text == null) 500 else 200
            val payload = if (text == null) """{"error":{"message":"眼睛罢工"}}""" else """{"choices":[{"message":{"content":"${text!!.replace("\"", "\\\"").replace("\n", " ") }"}}]}"""
            sink.onLine(payload)
            return WireResponse(code, null, payload.length.toLong(), if (code == 200) null else "眼睛罢工")
        }
        override fun cancel() {}
        override fun isCancelled(): Boolean = false
    }

    private fun seedVideo(inbox: File, frames: File, name: String, frameCount: Int = 3): File {
        val times = (1..frameCount).map { it * 5000L }
        val rels = times.mapIndexed { i, _ ->
            val rel = "$name/f$i.jpg"
            File(frames, rel).parentFile.mkdirs()
            File(frames, rel).writeBytes(jpeg)
            rel
        }
        inbox.mkdirs()
        val m = File(inbox, "$name.manifest.json")
        m.writeText(
            """{"name":"$name","durationMs":${frameCount * 6000L},"frameTimes":$times,"frames":$rels}""",
        )
        return m
    }

    private fun registry(inbox: File, frames: File, eye: ProviderSession?, transport: WireTransport): ToolRegistry {
        val r = ToolRegistry()
        VideoTool.register(r, inbox, frames, { eye }, transport)
        return r
    }

    @Test
    fun gatedByEyeModel() {
        val inbox = tmp.newFolder("inbox")
        val frames = tmp.newFolder("frames")
        assertTrue("没眼睛不注册（清单里不存在）：", registry(inbox, frames, null, FakeEye()).specs().isEmpty())
        assertEquals(2, registry(inbox, frames, session(), FakeEye()).size())
    }

    @Test
    fun listVideosReadsManifests() {
        val inbox = tmp.newFolder("inbox")
        val frames = tmp.newFolder("frames")
        seedVideo(inbox, frames, "拉面杯上")
        seedVideo(inbox, frames, "拉面杯下")
        val out = registry(inbox, frames, session(), FakeEye()).execute("list_videos", "{}")
        assertTrue(out.ok)
        assertTrue(out.text.contains("拉面杯上"))
        assertTrue(out.text.contains("拉面杯下"))
        assertTrue("报帧数与时长：${out.text}", out.text.contains("3 帧") && out.text.contains("18s"))
    }

    @Test
    fun listEmptyLibrarySaysSo() {
        val out = registry(tmp.newFolder("inbox"), tmp.newFolder("frames"), session(), FakeEye())
            .execute("list_videos", "{}")
        assertTrue(out.ok && out.text.contains("空的"))
    }

    @Test
    fun watchReturnsTextSummary() {
        val inbox = tmp.newFolder("inbox")
        val frames = tmp.newFolder("frames")
        seedVideo(inbox, frames, "决胜局", 4)
        val eye = FakeEye()
        val out = registry(inbox, frames, session(), eye).execute(
            "watch_video", """{"name":"决胜局","focus":"事件选项怎么选"}""",
        )
        assertTrue("看成功：${out.text}", out.ok && out.text.contains("【画面流水】"))
        assertTrue("带名字：${out.text}", out.text.contains("决胜局"))
        assertEquals("四帧全进了眼睛：", 4, eye.imagesSeen)
        assertTrue("focus 传给了汇总：${eye.lastRequest?.body}", eye.lastRequest?.body?.contains("事件选项怎么选") == true)
    }

    @Test
    fun watchUnknownNameListsWhatExists() {
        val inbox = tmp.newFolder("inbox")
        val frames = tmp.newFolder("frames")
        seedVideo(inbox, frames, "有的")
        val out = registry(inbox, frames, session(), FakeEye()).execute("watch_video", """{"name":"没有的"}""")
        assertTrue(out.ok)
        assertTrue("报没有+列有啥：${out.text}", out.text.contains("没有叫") && out.text.contains("有的"))
    }

    @Test
    fun watchBatchFailureStillReturnsNotes() {
        val inbox = tmp.newFolder("inbox")
        val frames = tmp.newFolder("frames")
        seedVideo(inbox, frames, "中断", 4)
        val eye = FakeEye().apply { failBatch1 = true }
        val out = registry(inbox, frames, session(), eye).execute("watch_video", """{"name":"中断"}""")
        assertTrue("批挂了也要交底：${out.text}", out.ok && out.text.contains("原始描述"))
    }

    @Test
    fun watchSummaryFailureStillReturnsNotes() {
        val inbox = tmp.newFolder("inbox")
        val frames = tmp.newFolder("frames")
        seedVideo(inbox, frames, "汇总挂", 2)
        val eye = FakeEye().apply { failSummary = true }
        val out = registry(inbox, frames, session(), eye).execute("watch_video", """{"name":"汇总挂"}""")
        assertTrue("汇总挂不白跑：${out.text}", out.ok && out.text.contains("汇总没成") && out.text.contains("画面照实读"))
    }

    @Test
    fun missingFrameFilesSkippedNotFatal() {
        val inbox = tmp.newFolder("inbox")
        val frames = tmp.newFolder("frames")
        val m = seedVideo(inbox, frames, "缺帧", 3)
        // 从账本拿第二帧的相对路径，把帧文件删掉
        val rel = VideoTool.readManifest(m)!!.frames[1]
        assertTrue(File(frames, rel).delete())
        val eye = FakeEye()
        val out = registry(inbox, frames, session(), eye).execute("watch_video", """{"name":"缺帧"}""")
        assertTrue("缺一帧照看：${out.text}", out.ok && out.text.contains("【画面流水】"))
        assertEquals("只进了两帧：", 2, eye.imagesSeen)
    }
}
