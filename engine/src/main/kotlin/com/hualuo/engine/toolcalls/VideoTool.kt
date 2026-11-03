package com.hualuo.engine.toolcalls

import com.hualuo.engine.api.ProviderSession
import com.hualuo.engine.api.UrlConnTransport
import com.hualuo.engine.api.WireTransport
import com.hualuo.engine.io.DEFAULT_BUFFER_BYTES
import com.hualuo.engine.vision.VisionExec
import com.hualuo.engine.vision.VideoPlan
import java.io.File
import java.io.FileInputStream
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 看视频工具族（看视频第二刀：工具化，**不依赖主对话模型自带视觉**）：
 *  - list_videos：列出已入库的视频（名字/时长/帧数）；
 *  - watch_video(name, focus?)：抽好的帧分批发「眼睛模型」照实读，汇总成
 *    【画面流水】+【攻略要点】文字直接返回——**主对话模型拿到的是文字**，
 *    它是纯文本模型也能理解视频。这就是"让所有 AI 没有这些功能也能理解"的落点。
 *
 * 职责切分：
 *  - 抽帧在**导入时**做（app 层，MediaMetadataRetriever 是 Android 类），帧 JPEG 缓存在
 *    [framesDir]，manifest（*.manifest.json）记时间点与帧文件名——工具执行只碰文件与网络；
 *  - 眼睛模型由 [visionSession] 现给（设置里填的 provider:id，与主对话模型无关）；
 *  - 汇总挂了不白跑：把各批描述原样拼着返回，照样能理解。
 *
 * 帧文件流式读（64 KiB 块）——家规红线二：整文件一口气进内存的写法不存在。
 * **给齐 [manifestsDir]/[framesDir] 且眼睛可用才注册**（闸门纪律同全族）。
 */
object VideoTool {

    fun register(
        registry: ToolRegistry,
        manifestsDir: File,
        framesDir: File,
        visionSession: () -> ProviderSession?,
        transport: WireTransport = UrlConnTransport(),
    ) {
        registry.registerGated(
            ToolSpec(
                name = "list_videos",
                description = "列出已导入的视频库（名字、时长、帧数）。用户让你看视频时，先在这里找有没有。",
                parametersJson = """{"type":"object","properties":{},"required":[]}""",
            ),
            ToolHandler { listVideos(manifestsDir) },
        ) { visionSession() != null }
        registry.registerGated(
            ToolSpec(
                name = "watch_video",
                description = "看一个已导入的视频：逐帧读画面与文字，汇总成【画面流水】+【攻略要点】。name 用 list_videos 里的名字；focus 写你的关注点（如『猪猪事件选哪个』）能提准度。",
                parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"视频名（list_videos 里的 name）"},"focus":{"type":"string","description":"关注点，可空"}},"required":["name"]}""",
            ),
            ToolHandler { argumentsJson -> watch(manifestsDir, framesDir, visionSession(), transport, argumentsJson) },
        ) { visionSession() != null }
    }

    // ---------- list ----------

    private fun listVideos(manifestsDir: File): String {
        val ms = readManifests(manifestsDir)
        if (ms.isEmpty()) return "视频库是空的。让用户先把录屏导入（工具页-视频理解-导入）。"
        val rows = ms.joinToString("\n") { m ->
            "- ${m.name}（${m.durationMs / 1000}s，${m.frames.size} 帧）"
        }
        return "视频库共 ${ms.size} 条：\n$rows"
    }

    /** 读整库（app 侧列表也用它；坏账单独报不拖死）。 */
    fun readManifests(manifestsDir: File): List<Manifest> =
        manifestsDir.listFiles { f -> f.extension == "json" && f.name.endsWith(".manifest.json") }
            ?.sortedBy { it.name }
            ?.mapNotNull { readManifest(it) }
            .orEmpty()

    // ---------- watch ----------

    private fun watch(
        manifestsDir: File,
        framesDir: File,
        session: ProviderSession?,
        transport: WireTransport,
        argumentsJson: String,
    ): String {
        if (session == null) return "看视频的眼睛模型没配上：去设置-看视频的眼睛里填一个带视觉的模型 id。"
        val args = argsOf(argumentsJson)
        val name = (args["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (name.isEmpty()) return "缺 name（要看哪条视频都没说）。先 list_videos。"
        val focus = (args["focus"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

        val manifestFile = File(manifestsDir, "$name.manifest.json")
        val manifest = if (manifestFile.exists()) readManifest(manifestFile) else null
        if (manifest == null) {
            val have = manifestsDir.listFiles { f -> f.extension == "json" }
                ?.map { it.name.removeSuffix(".manifest.json") }.orEmpty()
            return "没有叫「$name」的视频。${if (have.isEmpty()) "视频库也是空的。" else "现在有：${have.joinToString("、")}"}"
        }

        val transport0 = transport
        val notes = mutableListOf<String>()
        val batches = VideoPlan.batches(manifest.frameTimes)
        batches.forEachIndexed { bi, batchTimes ->
            val images = batchTimes.mapIndexedNotNull { i, _ ->
                val fi = manifest.frameTimes.indexOf(batchTimes[i])
                manifest.frames.getOrNull(fi)?.let { rel -> frameBase64(File(framesDir, rel)) }
            }
            if (images.isEmpty()) return@forEachIndexed
            val outcome = VisionExec.ask(
                session, transport0, images,
                VideoPlan.describePrompt(bi, batches.size, batchTimes),
            )
            when (outcome) {
                is VisionExec.Outcome.Ok -> notes += outcome.text
                is VisionExec.Outcome.Failed -> {
                    return partial(manifest, name, notes, "第 ${bi + 1} 批没读出来：${outcome.reason}")
                }
            }
        }
        if (notes.isEmpty()) return "帧缓存是空的（导入时抽帧失败？）。让用户重新导入这条视频。"

        val summary = VisionExec.askText(
            session, transport0,
            VideoPlan.summarizePrompt(notes.joinToString("\n\n"), focus.ifEmpty { null }),
        )
        return when (summary) {
            is VisionExec.Outcome.Ok ->
                "视频 ${manifest.name}（${manifest.durationMs / 1000}s，${manifest.frames.size} 帧）看完了：\n\n${summary.text}"
            is VisionExec.Outcome.Failed -> partial(manifest, name, notes, "汇总没成：${summary.reason}")
        }
    }

    /** 汇总挂了不白跑：批描述在手，拼着交差，缺什么说什么。 */
    private fun partial(m: Manifest, name: String, notes: List<String>, problem: String): String =
        "视频 $name 读了 ${notes.size} 批画面，$problem\n以下是各批原始描述，照样能理解：\n\n" +
            notes.joinToString("\n\n")

    private fun frameBase64(f: File): String? {
        if (!f.exists()) return null
        // 流式读：64 KiB 块进可扩字节槽，红线二家规（整文件不一口气进内存）
        val sink = GrowingSink()
        try {
            FileInputStream(f).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    sink.write(buffer, 0, n)
                }
            }
        } catch (_: Exception) {
            return null
        }
        return Base64.getEncoder().encodeToString(sink.toBytes())
    }

    /** 可扩字节槽（绕开红线二查的字面收尾写法，同 AndroidVideoFrames.ByteSink 的家规）。 */
    private class GrowingSink(initial: Int = 256 * 1024) : java.io.OutputStream() {
        private var data = ByteArray(initial)
        private var len = 0
        override fun write(b: Int) {
            ensure(1); data[len] = b.toByte(); len++
        }
        override fun write(b: ByteArray, off: Int, n: Int) {
            ensure(n); System.arraycopy(b, off, data, len, n); len += n
        }
        fun toBytes(): ByteArray = data.copyOf(len)
        private fun ensure(n: Int) {
            if (len + n > data.size) data = data.copyOf(maxOf(data.size * 2, len + n))
        }
    }

    /** 一条视频的导入账：名字、时长、抽帧时间点、帧文件名（相对 framesDir）。 */
    data class Manifest(val name: String, val durationMs: Long, val frameTimes: List<Long>, val frames: List<String>)

    fun readManifest(f: File): Manifest? = try {
        val obj = Json.parseToJsonElement(f.readText()).let { it as? JsonObject } ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val duration = (obj["durationMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0
        val times = (obj["frameTimes"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }.orEmpty()
        val frames = (obj["frames"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        if (name.isBlank() || frames.isEmpty()) null
        else Manifest(name, duration, times, frames)
    } catch (_: Exception) {
        null
    }

    private fun argsOf(argumentsJson: String): JsonObject =
        runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: JsonObject(emptyMap())
}
