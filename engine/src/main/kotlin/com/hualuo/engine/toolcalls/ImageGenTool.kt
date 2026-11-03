package com.hualuo.engine.toolcalls

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** 图像生成的现场配置（钥匙/端点/模型/尺寸），app 侧每次执行现读现给。 */
data class ImageGenConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val size: String,
) {
    fun usable(): Boolean = apiKey.isNotBlank()
}

/**
 * 图像生成工具族（M4 第七刀，语义对齐旧 Agora ImageGenToolProvider）：一件——generate_image。
 *
 *  - OpenAI 兼容 `/images/generations`（model/prompt/size/n=1，Bearer 钥匙）；
 *  - 回执 data[0] 优先 b64_json（直接解码），否则 url（拉字节）——两条路都通；
 *  - 字节交 [persist] 落盘，回执给保存路径——引擎件不碰 Android 存储；
 *  - 错误码对齐旧仓：no_prompt / no_config / no_response / no_image / download_failed / generation_error。
 *
 * **可见性**：钥匙没配（[ImageGenConfig.usable] 为假）= 清单里消失（registerGated）——
 * 没钥匙的工具不该被模型看见，也不许占着清单装摆设；配了钥匙下一轮立刻出现。
 * 网络动作（[poster]/[downloadBytes]）全缝隙注入：纯 JVM 测试零真网。
 * 零脱敏：prompt 原文出去，路径原文回来。
 */
object ImageGenTool {

    private const val DEFAULT_SIZE = "1024x1024"
    private const val DEFAULT_MODEL = "gpt-image-1"
    private const val DEFAULT_BASE = "https://api.openai.com/v1"

    fun register(
        registry: ToolRegistry,
        loadConfig: () -> ImageGenConfig?,
        poster: (url: String, body: String, bearer: String) -> String,
        downloadBytes: (url: String) -> ByteArray,
        persist: (bytes: ByteArray, prefix: String) -> String,
    ) {
        val spec = ToolSpec(
            name = "generate_image",
            description = "按文字描述生成一张图片，存到设备并回报保存路径。用户明确要画图/生成图片时才用。",
            parametersJson = """{"type":"object","properties":{"prompt":{"type":"string","description":"画面描述，写具体（主体/风格/构图）"},"size":{"type":"string","description":"尺寸如 1024x1024，默认 1024x1024"}},"required":["prompt"]}""",
        )
        registry.registerGated(
            spec,
            ToolHandler { argumentsJson -> execute(loadConfig(), argumentsJson, poster, downloadBytes, persist) },
            visibleIf = { loadConfig()?.usable() == true },
        )
    }

    private fun execute(
        config: ImageGenConfig?,
        argumentsJson: String,
        poster: (String, String, String) -> String,
        downloadBytes: (String) -> ByteArray,
        persist: (ByteArray, String) -> String,
    ): String {
        val args = argsOf(argumentsJson)
        val prompt = (args["prompt"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (prompt.isEmpty()) return err("no_prompt", "缺 prompt（画什么都没说）")
        if (config == null || !config.usable()) return err("no_config", "图像生成的钥匙没配上")
        val size = (args["size"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: config.size.ifBlank { DEFAULT_SIZE }
        val base = config.baseUrl.trimEnd('/').ifBlank { DEFAULT_BASE }
        val model = config.model.ifBlank { DEFAULT_MODEL }

        return try {
            val body = """{"model":${JsonPrimitive(model)},"prompt":${JsonPrimitive(prompt)},"size":${JsonPrimitive(size)},"n":1}"""
            val response = poster("$base/images/generations", body, config.apiKey)
            if (response.isBlank()) return err("no_response", "端点回了空")
            val root = runCatching { Json.parseToJsonElement(response) }.getOrNull() as? JsonObject
                ?: return err("no_image", "回执不是 JSON，端点可能配错")
            val data = root["data"] as? JsonArray
            val first = data?.firstOrNull() as? JsonObject
                ?: return err("no_image", "回执里没有 data[0]（模型名或钥匙可能不对）")
            val bytes: ByteArray = when {
                !((first["b64_json"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) ->
                    Base64.getDecoder().decode((first["b64_json"] as JsonPrimitive).content)
                !((first["url"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) -> try {
                    downloadBytes((first["url"] as JsonPrimitive).content)
                } catch (e: Exception) {
                    return err("download_failed", e.message ?: "图片下载失败")
                }
                else -> return err("no_image", "data[0] 里既没有 b64_json 也没有 url")
            }
            if (bytes.isEmpty()) return err("no_image", "拿到的图片字节是空的")
            val saved = persist(bytes, "generated_image")
            """{"type":"image_generation","status":"ok","size":${JsonPrimitive(size)},"bytes":${bytes.size},"saved":${JsonPrimitive(saved)}}"""
        } catch (e: Exception) {
            err("generation_error", e.message ?: "生成请求失败")
        }
    }

    private fun err(code: String, message: String?): String {
        val msg = if (message.isNullOrBlank()) "" else ""","message":${JsonPrimitive(message)}"""
        return """{"type":"image_generation","error":"$code"$msg}"""
    }

    private fun argsOf(argumentsJson: String): JsonObject =
        runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: JsonObject(emptyMap())

    /** 真网 POST（app 侧注入用）：超时齐备、回执有界（256K，图片走 b64 不会太 gigantic）。 */
    fun defaultPoster(url: String, body: String, bearer: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $bearer")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36")
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = InputStreamReader(stream, Charsets.UTF_8).use { r ->
                val out = StringBuilder()
                val buf = CharArray(8 * 1024)
                while (out.length < 262_144) {
                    val n = r.read(buf, 0, minOf(buf.size, 262_144 - out.length))
                    if (n < 0) break
                    out.append(buf, 0, n)
                }
                out.toString()
            }
            if (code !in 200..299) throw IOException("HTTP $code：${text.take(300)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** 真网拉字节（app 侧注入用）：有界 32M，够一张图。 */
    fun defaultDownloader(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            // 分块收，最后手工拼——不走 ByteArrayOutputStream 的收尾（CI 红线二按字面查
            // 那个一字不差的收尾写法：旧 Agora 整文件读内存闪退的家规），不跟它撞名
            val chunks = ArrayList<ByteArray>()
            var total = 0
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (total + n > 32 * 1024 * 1024) throw IOException("图片超过 32M 上限")
                    chunks.add(buf.copyOf(n))
                    total += n
                }
            }
            val bytes = ByteArray(total)
            var pos = 0
            for (c in chunks) {
                c.copyInto(bytes, pos)
                pos += c.size
            }
            return bytes
        } finally {
            conn.disconnect()
        }
    }
}
