package io.github.jackwener.dsh.core.workbench

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/** Loopback-only receiver for hlpatch's existing POST 127.0.0.1:18766/data summary pushes. */
@Singleton
class UmaPushReceiver @Inject constructor(
    private val store: UmaFrameStore,
) {
    private var requestedPort = PORT
    private var maxBodyBytes = UmaHttpCodec.MAX_BODY_BYTES
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile var actualPort: Int = 0
        private set
    @Volatile private var lastError: String? = null
    @Volatile private var rejectedFrames: Long = 0L

    internal constructor(store: UmaFrameStore, port: Int, maxBodyBytes: Int = UmaHttpCodec.MAX_BODY_BYTES) : this(store) {
        requestedPort = port
        this.maxBodyBytes = maxBodyBytes
    }

    /** Idempotent. Bind errors are reported through /health and uma_push_status, never crash app startup. */
    @Synchronized
    fun start() {
        if (running) return
        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), requestedPort))
            server = socket
            actualPort = socket.localPort
            lastError = null
            running = true
            scope.launch { acceptLoop(socket) }
        } catch (e: Exception) {
            running = false
            lastError = e.message ?: e.javaClass.simpleName
        }
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (scope.isActive && running) {
            try {
                socket.accept().use { client -> handle(client) }
            } catch (e: IOException) {
                if (running) lastError = e.message ?: "accept failed"
            } catch (e: Exception) {
                if (running) lastError = e.message ?: e.javaClass.simpleName
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val request = UmaHttpCodec.read(client.getInputStream(), maxBodyBytes)
            when {
                request.method == "POST" && request.path == "/data" -> {
                    val contentType = request.headers["content-type"].orEmpty()
                    if (!contentType.substringBefore(';').trim().equals("application/json", ignoreCase = true)) {
                        throw UmaHttpCodec.HttpError(415, "Content-Type must be application/json")
                    }
                    val frame = try {
                        store.accept(String(request.body, Charsets.UTF_8))
                    } catch (e: Exception) {
                        throw UmaHttpCodec.HttpError(400, "invalid summary JSON: ${e.message ?: "parse failed"}")
                    }
                    respond(client, 200, buildJsonObject {
                        put("ok", true)
                        put("sequence", frame.sequence)
                        put("changedFields", frame.changedFields.size)
                    }.toString())
                }
                request.method == "GET" && request.path == "/health" -> respond(client, 200, status().toString())
                request.method == "GET" && request.path == "/data" -> {
                    respond(client, 200, (store.snapshot() ?: buildJsonObject {
                        put("sequence", 0L); put("data", kotlinx.serialization.json.JsonNull)
                    }).toString())
                }
                request.path !in setOf("/data", "/health") -> throw UmaHttpCodec.HttpError(404, "unknown path")
                else -> throw UmaHttpCodec.HttpError(405, "use POST /data, GET /data or GET /health")
            }
        } catch (e: UmaHttpCodec.HttpError) {
            rejectedFrames++
            respond(client, e.status, errorBody(e.message ?: "request rejected"))
        } catch (e: SocketTimeoutException) {
            rejectedFrames++
            respond(client, 408, errorBody("request timed out"))
        } catch (e: EOFException) {
            rejectedFrames++
            respond(client, 400, errorBody("incomplete HTTP request"))
        } catch (e: Exception) {
            rejectedFrames++
            respond(client, 400, errorBody(e.message ?: "request failed"))
        }
    }

    private fun respond(client: Socket, status: Int, body: String) {
        runCatching { UmaHttpCodec.write(client.getOutputStream(), status, body) }
    }

    fun status() = buildJsonObject {
        put("running", running)
        put("bind", LOOPBACK)
        put("port", actualPort.takeIf { running } ?: requestedPort)
        put("maxBodyBytes", maxBodyBytes)
        put("rejectedFrames", rejectedFrames)
        put("lastError", lastError?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        val frame = store.latest()
        put("sequence", frame?.sequence ?: 0L)
        put("receivedAtMs", frame?.receivedAtMs?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        put("hasSnapshot", frame != null)
    }

    private fun errorBody(message: String) = buildJsonObject {
        put("ok", false); put("error", message)
    }.toString()

    companion object {
        const val PORT = 18766
        const val LOOPBACK = "127.0.0.1"
        private const val SOCKET_TIMEOUT_MS = 15_000
    }
}
