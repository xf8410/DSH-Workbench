package io.github.jackwener.dsh.core.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UmaHttpCodecTest {
    @Test fun `reads exact hlpatch content length including utf8`() {
        val body = """{"scenario":"祭典杯","stats":{"speed":1200}}"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val wire = "POST /data HTTP/1.1\r\nHost: 127.0.0.1:18766\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        val request = UmaHttpCodec.read((wire.toByteArray() + bytes).inputStream())
        assertEquals("POST", request.method)
        assertEquals("/data", request.path)
        assertEquals(body, String(request.body, Charsets.UTF_8))
    }

    @Test fun `rejects missing length chunked and oversized bodies`() {
        val missing = "POST /data HTTP/1.1\r\nContent-Type: application/json\r\n\r\n{}"
        assertEquals(411, assertThrows(UmaHttpCodec.HttpError::class.java) {
            UmaHttpCodec.read(missing.toByteArray().inputStream())
        }.status)
        val chunked = "POST /data HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n"
        assertEquals(400, assertThrows(UmaHttpCodec.HttpError::class.java) {
            UmaHttpCodec.read(chunked.toByteArray().inputStream())
        }.status)
        val large = "POST /data HTTP/1.1\r\nContent-Length: 65\r\n\r\n"
        assertEquals(413, assertThrows(UmaHttpCodec.HttpError::class.java) {
            UmaHttpCodec.read(large.toByteArray().inputStream(), maxBodyBytes = 64)
        }.status)
    }
}
