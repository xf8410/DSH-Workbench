package com.hualuo.engine.lsp

/**
 * LSP 的 JSON-RPC 帧编解码（纯 JVM，无 IO）。
 *
 * LSP 走 `Content-Length: N\r\n\r\n<正文>` 的帧：
 *  - 长度按**字节**算不是字符（中文/表情在正文里时两者不同，按字符算会截断——经典坑）；
 *  - 头部可以有多个（Content-Type 等），只认 Content-Length，其余跳过；
 *  - 头部大小写不敏感；
 *  - 有硬上限 [MAX_BODY_BYTES]：坏客户端/坏服务端报个离谱长度时当场判死，不许 malloc。
 *
 * 解码器是无状态纯函数 + 一个小状态机：喂进来一整块字节，能解几帧解几帧，
 * 剩下的零头还给调用方（下次接着喂）。收场三态：解析出的帧 / 还缺料 / 判死（错误）。
 */
object LspFraming {

    /** 单帧正文上限：8 MiB（远超任何正常 LSP 消息；超了就是对方的错）。 */
    const val MAX_BODY_BYTES = 8 * 1024 * 1024

    /** 编码一帧：`Content-Length: N\r\n\r\n` + 正文（N 按 UTF-8 字节数）。 */
    fun encode(body: String): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.UTF_8)
        return header + bytes
    }

    /** 解码结果：帧列表 + 没吃完的零头；[fatal] 非空 = 帧格式判死（调用方应关连接）。 */
    data class Decoded(val messages: List<String>, val remainder: ByteArray, val fatal: String?) {
        override fun equals(other: Any?): Boolean =
            other is Decoded && messages == other.messages &&
                remainder.contentEquals(other.remainder) && fatal == other.fatal

        override fun hashCode(): Int =
            (messages.hashCode() * 31 + remainder.contentHashCode()) * 31 + (fatal?.hashCode() ?: 0)
    }

    /**
     * 解一坨字节：能解几帧解几帧。**剩余零头必须还给调用方**——
     * 别丢，也别假设「这一块正好是一帧」（TCP 不保证）。
     */
    fun decode(buffer: ByteArray): Decoded {
        val messages = ArrayList<String>()
        var offset = 0
        while (true) {
            // 找头部结束（\r\n\r\n）
            val headerEnd = indexOfHeaderEnd(buffer, offset) ?: break
            if (headerEnd < 0) break
            val headerText = String(buffer, offset, headerEnd - offset, Charsets.US_ASCII)
            val length = headerLength(headerText)
                ?: return Decoded(messages, buffer.copyOfRange(offset, buffer.size), "帧头没有 Content-Length（对方不按 LSP 说话）")
            if (length < 0) {
                return Decoded(messages, ByteArray(0), "Content-Length 是负数：$length")
            }
            if (length > MAX_BODY_BYTES) {
                return Decoded(
                    messages,
                    ByteArray(0),
                    "帧正文声明 $length 字节，超了 $MAX_BODY_BYTES 上限：当场判死（不分配这种内存）",
                )
            }
            val bodyStart = headerEnd + 4
            val bodyEnd = bodyStart + length
            if (bodyEnd > buffer.size) {
                // 还没读全：从本帧头部开始整段留到下一轮
                return Decoded(messages, buffer.copyOfRange(offset, buffer.size), null)
            }
            messages += String(buffer, bodyStart, length, Charsets.UTF_8)
            offset = bodyEnd
            if (offset >= buffer.size) break
        }
        return Decoded(messages, buffer.copyOfRange(offset, buffer.size), null)
    }

    /** 找 `\r\n\r\n` 的位置（返回头部最后一字节的下一个位置=四字节序列的起点的下标；找不到给 null）。 */
    private fun indexOfHeaderEnd(buf: ByteArray, from: Int): Int? {
        var i = from
        while (i + 3 < buf.size) {
            if (buf[i] == 13.toByte() && buf[i + 1] == 10.toByte() &&
                buf[i + 2] == 13.toByte() && buf[i + 3] == 10.toByte()
            ) {
                return i
            }
            i += 1
        }
        return null
    }

    /** 从头部文本取 Content-Length（大小写不敏感；没有给 null；不是数字也给 null 之外的空）。 */
    private fun headerLength(headerText: String): Int? {
        for (line in headerText.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase()
            if (name != "content-length") continue
            return line.substring(idx + 1).trim().toIntOrNull() ?: -1
        }
        return null
    }
}

/**
 * LSP 消息的小工厂：请求 / 通知 / 响应（用来拼 initialize、didOpen、shutdown 这些）。
 * 只做形状，不认识语义（语义归调用方）。
 */
object LspMessages {

    /** 请求：`{"jsonrpc":"2.0","id":N,"method":...,"params":...}`。 */
    fun request(id: Int, method: String, paramsJson: String? = null): String = buildString {
        append("{\"jsonrpc\":\"2.0\",\"id\":").append(id)
        append(",\"method\":\"").append(escape(method)).append("\"")
        if (paramsJson != null) append(",\"params\":").append(paramsJson)
        append("}")
    }

    /** 通知：无 id（报错也不回）。 */
    fun notification(method: String, paramsJson: String? = null): String = buildString {
        append("{\"jsonrpc\":\"2.0\",\"method\":\"").append(escape(method)).append("\"")
        if (paramsJson != null) append(",\"params\":").append(paramsJson)
        append("}")
    }

    /** 响应（回对方的请求）：result 是 JSON 原文。 */
    fun response(id: Int, resultJson: String): String =
        "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$resultJson}"

    /** 响应（错误）：code 用 JSON-RPC 标准的负数码。 */
    fun errorResponse(id: Int, code: Int, message: String): String =
        "{\"jsonrpc\":\"2.0\",\"id\":$id,\"error\":{\"code\":$code,\"message\":\"${escape(message)}\"}}"

    /** 字符串转义（只处理必要四样；method/message 不会有别的怪字符）。 */
    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}
