package io.github.jackwener.dsh.core.workbench

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket

class UmaPushReceiverTest {
    private val store = UmaFrameStore()
    private val receiver = UmaPushReceiver(store, port = 0, maxBodyBytes = 64)

    @Before fun start() { receiver.start(); assertTrue(receiver.status().toString().contains("true")) }
    @After fun stop() { receiver.stop() }

    @Test fun `real socket push health snapshot and errors`() {
        val body = """{"turn":3,"matsuri":{"festival":2}}"""
        val post = request("POST", "/data", body)
        val accepted = exchange(post)
        assertTrue(accepted.startsWith("HTTP/1.1 200"))
        assertTrue(accepted.contains("\"sequence\":1"))
        assertEquals(1L, store.latest()?.sequence)

        val health = exchange(request("GET", "/health"))
        assertTrue(health.startsWith("HTTP/1.1 200"))
        assertTrue(health.contains("\"hasSnapshot\":true"))
        val snapshot = exchange(request("GET", "/data"))
        assertTrue(snapshot.contains("\"matsuri\":{\"festival\":2}"))

        val badJson = exchange(request("POST", "/data", "{"))
        assertTrue(badJson.startsWith("HTTP/1.1 400"))
        assertEquals(1L, store.latest()?.sequence)
        assertTrue(exchange(request("GET", "/missing")).startsWith("HTTP/1.1 404"))

        val tooLarge = "POST /data HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: 65\r\nConnection: close\r\n\r\n"
        assertTrue(exchange(tooLarge).startsWith("HTTP/1.1 413"))
        assertEquals(1L, store.latest()?.sequence)
    }

    private fun request(method: String, path: String, body: String? = null): String {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        return "$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
            (if (bytes != null) "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" else "") +
            "Connection: close\r\n\r\n" + (body ?: "")
    }

    private fun exchange(wire: String): String = Socket("127.0.0.1", receiver.actualPort).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(wire.toByteArray(Charsets.UTF_8))
        socket.getOutputStream().flush()
        String(socket.getInputStream().readBytes(), Charsets.UTF_8)
    }
}
