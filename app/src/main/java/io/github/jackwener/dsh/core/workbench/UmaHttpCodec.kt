package io.github.jackwener.dsh.core.workbench

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Minimal bounded HTTP/1.x codec for the hlpatch POST /data wire protocol. */
internal object UmaHttpCodec {
    const val MAX_BODY_BYTES = 8 * 1024 * 1024
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_LINE_BYTES = 8 * 1024

    data class Request(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        val path: String get() = target.substringBefore('?')
    }

    class HttpError(val status: Int, message: String) : Exception(message)

    fun read(input: InputStream, maxBodyBytes: Int = MAX_BODY_BYTES): Request {
        var headerBytes = 0
        fun line(): String {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("unexpected end of HTTP headers")
                headerBytes++
                if (headerBytes > MAX_HEADER_BYTES || out.size() > MAX_LINE_BYTES) {
                    throw HttpError(431, "HTTP headers are too large")
                }
                if (b == '\n'.code) break
                if (b != '\r'.code) out.write(b)
            }
            return String(out.toByteArray(), StandardCharsets.ISO_8859_1)
        }

        val requestLine = line()
        if (requestLine.isEmpty()) throw HttpError(400, "missing HTTP request line")
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")) {
            throw HttpError(400, "malformed HTTP request line")
        }
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = line()
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpError(400, "malformed HTTP header")
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            val old = headers.put(name, value)
            if (old != null && old != value) throw HttpError(400, "conflicting duplicate header: $name")
        }
        if (headers.containsKey("transfer-encoding")) {
            throw HttpError(400, "chunked requests are not supported; send Content-Length")
        }
        val length = headers["content-length"]?.let {
            it.toLongOrNull()?.takeIf { n -> n >= 0L } ?: throw HttpError(400, "invalid Content-Length")
        }
        if (parts[0].equals("POST", ignoreCase = true) && length == null) {
            throw HttpError(411, "POST requires Content-Length")
        }
        val size = length ?: 0L
        if (size > maxBodyBytes) throw HttpError(413, "request body exceeds $maxBodyBytes bytes")
        val body = ByteArray(size.toInt())
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            if (count < 0) throw HttpError(400, "request body ended before Content-Length")
            offset += count
        }
        return Request(parts[0].uppercase(), parts[1], headers, body)
    }

    fun write(output: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            408 -> "Request Timeout"; 411 -> "Length Required"; 413 -> "Content Too Large"
            415 -> "Unsupported Media Type"; 431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"; else -> "Response"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }
}
