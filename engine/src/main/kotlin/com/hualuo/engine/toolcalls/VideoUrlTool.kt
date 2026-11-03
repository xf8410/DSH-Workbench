package com.hualuo.engine.toolcalls

import com.hualuo.engine.api.ProviderProtocol
import com.hualuo.engine.api.UrlConnTransport
import com.hualuo.engine.vision.VideoUrlPolicy
import com.hualuo.engine.vision.VisionExec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * analyze_video_url（看视频刀第二件，语义对齐审查意见的服务端视频架构）：
 * 用户给视频链接 -> 主模型调本工具 -> **模型服务自己去远端读视频** -> 返回摘要。
 * 手机端不下载、不抽帧、不占存储。
 *
 * 诚实边界（红线）：
 *  - 仅 Gemini 协议执行（fileData.fileUri）；其他协议**如实拒**并指引用户切模型，
 *    绝不偷偷降级成本地下载抽帧（那会改变成本/存储/隐私边界）；
 *  - URL 过 VideoUrlPolicy 安检：内网/元数据地址拒；只放 YouTube 与直接视频文件 URL，
 *    普通网页/平台分享页/HLS 第一版明确拒（不装「我去看一下」）；
 *  - 服务端读不了（链接非直链、格式不支持）就把服务端错误原文返回——错误是服务端说的。
 *
 * 可见性：sessionProvider() 非空即注册（模型要知道有这个能力并能引导用户切模型）；
 * 执行侧再按协议如实校验。
 */
object VideoUrlTool {

    fun register(
        registry: ToolRegistry,
        sessionProvider: () -> com.hualuo.engine.api.ProviderSession?,
    ) {
        val spec = ToolSpec(
            name = "analyze_video_url",
            description = "分析一个视频链接的内容（服务端原生视频理解，不占本机存储）。" +
                "参数：url（视频链接）+ goal（想从视频里了解什么，可选）。" +
                "支持 YouTube 链接与直接视频文件 URL（.mp4/.webm/.mov 等）；普通网页或平台分享页不支持。" +
                "当前聊天模型是 Gemini 系才能执行；其他模型会如实拒绝。用户发来视频链接时用这个工具。",
            parametersJson = """{"type":"object","properties":{"url":{"type":"string","description":"视频链接（YouTube 或直接视频文件 URL）"},"goal":{"type":"string","description":"想从视频里总结出什么（画面内容/攻略要点/操作流程），留空就做通用总结"}},"required":["url"]}""",
        )
        registry.registerGated(
            spec,
            ToolHandler { argumentsJson ->
                val session = sessionProvider()
                    ?: return@ToolHandler """{"type":"analyze_video_url","error":"no_session","message":"没有可用的模型会话"}"""
                val args = argsOf(argumentsJson)
                val url = (args["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (url.isEmpty()) {
                    return@ToolHandler """{"type":"analyze_video_url","error":"no_url"}"""
                }
                val goal = (args["goal"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                when (val verdict = VideoUrlPolicy.check(url)) {
                    is VideoUrlPolicy.Verdict.Rejected ->
                        return@ToolHandler """{"type":"analyze_video_url","error":"url_rejected","message":${JsonPrimitive(verdict.reason)}}"""
                    is VideoUrlPolicy.Verdict.Allowed -> Unit
                }
                if (session.protocol != ProviderProtocol.GEMINI) {
                    return@ToolHandler """{"type":"analyze_video_url","error":"protocol_unsupported","message":"当前模型协议 ${session.protocol} 不支持服务端视频输入；把聊天模型切到 Gemini 系（或在提供商设置里加 Google）再用。本工具不偷偷降级成本地下载抽帧。"}"""
                }
                val instruction = buildString {
                    append("看完这个视频，整理成：【画面流水】按时间讲清发生了什么；")
                    append("【关键文字/数值】原样保留视频里出现的选项、数值、提示文字；")
                    if (goal.isNotBlank()) append("用户的关注点：$goal。")
                    append("看不清或听不清的地方标注「不清」，不许猜。")
                }
                val outcome = VisionExec.askVideoUrl(session, UrlConnTransport(), url, instruction)
                when (outcome) {
                    is VisionExec.Outcome.Ok ->
                        """{"type":"analyze_video_url","status":"ok","url":${JsonPrimitive(url)},"summary":${JsonPrimitive(outcome.text)}}"""
                    is VisionExec.Outcome.Failed ->
                        """{"type":"analyze_video_url","error":"analysis_failed","message":${JsonPrimitive(outcome.reason)}}"""
                }
            },
            visibleIf = { sessionProvider() != null },
        )
    }

    private fun argsOf(argumentsJson: String): JsonObject =
        runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: JsonObject(emptyMap())
}
