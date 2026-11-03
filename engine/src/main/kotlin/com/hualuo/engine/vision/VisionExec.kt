package com.hualuo.engine.vision

import com.hualuo.engine.api.ProviderProtocol
import com.hualuo.engine.api.ProviderSession
import com.hualuo.engine.api.WireRequest
import com.hualuo.engine.api.WireTransport
import com.hualuo.engine.api.WireResponse
import com.hualuo.engine.api.LineSink
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException

/**
 * 视觉执行件（看视频功能第一刀）：把 [VisionTurns] 造的请求经 [WireTransport] 发出去，
 * 非流式收全文再提取文本。
 *
 * 会话由调用方给（[ProviderSession] 来自多提供商设置，app 侧现读现给）；
 * transport 可注入——纯 JVM 测试喂假件零真网。
 *
 * 收法：WireTransport 是逐行喂的流式口子，非流式回执就是一大坨 JSON——
 * 这里把行攒起来当全文用，结束时提取文本。错误体（非 2xx）进 [Failed.reason]。
 */
object VisionExec {

    /** 一次视觉问答的收场：要么文本，要么能行动的失败理由。 */
    sealed class Outcome {
        data class Ok(val text: String) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /** 问一次（多图 + 一句）。协议不支持视觉（build 回 null）按失败说。 */
    fun ask(
        session: ProviderSession,
        transport: WireTransport,
        imagesBase64: List<String>,
        question: String,
    ): Outcome {
        val request: WireRequest = VisionTurns.build(session, imagesBase64, question)
            ?: return Outcome.Failed("通道「${session.protocol}」没接视觉请求，换 Google/OpenAI/Anthropic 系模型再用看视频")
        return sendAndExtract(session, transport, request)
    }

    /** 无图纯文本问一次（汇总段复用同一条通道）。 */
    fun askText(
        session: ProviderSession,
        transport: WireTransport,
        question: String,
    ): Outcome {
        val request: WireRequest = VisionTurns.build(session, listOf("iVBORw0KGgo="), question)
            ?: return Outcome.Failed("通道「${session.protocol}」没接视觉请求")
        // 汇总不该夹一张假图——用文本形状重发：OpenAI/Gemini/Anthropic 的纯文本形状
        val textRequest = when (session.protocol) {
            ProviderProtocol.OPENAI_COMPAT -> request.copy(
                body = textOpenAiBody(session, question),
            )
            ProviderProtocol.GEMINI -> request.copy(
                body = textGeminiBody(session, question),
            )
            ProviderProtocol.ANTHROPIC -> request.copy(
                body = textAnthropicBody(session, question),
            )
            else -> return Outcome.Failed("通道「${session.protocol}」没接视觉请求")
        }
        return sendAndExtract(session, transport, textRequest)
    }

    private fun sendAndExtract(
        session: ProviderSession,
        transport: WireTransport,
        request: WireRequest,
    ): Outcome {
        val full = StringBuilder()
        val response: WireResponse = try {
            transport.exchange(request) { line ->
                full.append(line).append('\n')
                true
            }
        } catch (e: IOException) {
            return Outcome.Failed("连不上模型（${e.message ?: "网络错误"}）")
        }
        if (response.status !in 200..299) {
            val detail = VisionTurns.extractError(response.errorBody.orEmpty())
            return Outcome.Failed("模型回 ${response.status}：" + (detail ?: response.errorBody?.take(300) ?: "无详情"))
        }
        val text = VisionTurns.extractText(session.protocol, full.toString())
        if (text.isBlank()) return Outcome.Failed("回执里没读出正文（形状变了或被空回执应付）")
        return Outcome.Ok(text)
    }

    // ---------- 纯文本 body（汇总段用；形状与带图版一致只少图） ----------

    private fun textOpenAiBody(session: ProviderSession, question: String): String =
        buildJsonObject {
            put("model", session.profile.model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user"); put("content", question)
                }
            }
        }.toString()

    private fun textGeminiBody(session: ProviderSession, question: String): String =
        buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", question) }
                    }
                }
            }
        }.toString()

    private fun textAnthropicBody(session: ProviderSession, question: String): String =
        buildJsonObject {
            put("model", session.profile.model)
            put("max_tokens", 4096)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user"); put("content", question)
                }
            }
        }.toString()


    /** 问一次**服务端视频 URL**（Gemini fileData；其他协议按失败如实回）。 */
    fun askVideoUrl(
        session: ProviderSession,
        transport: WireTransport,
        url: String,
        instruction: String,
    ): Outcome {
        val request = VisionTurns.buildVideoUrlRequest(session, url, instruction)
            ?: return Outcome.Failed(
                "当前模型协议（${session.protocol}）不支持服务端视频输入；把聊天模型切到 Gemini 系再试。" +
                    "本工具不偷偷降级成本地下载抽帧。"
            )
        return sendAndExtract(session, transport, request)
    }
}
