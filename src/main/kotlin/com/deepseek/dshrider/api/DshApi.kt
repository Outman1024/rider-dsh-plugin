package com.deepseek.dshrider.api

import com.deepseek.dshrider.wire.DshFrame
import com.deepseek.dshrider.wire.DshRpcResult
import com.deepseek.dshrider.wire.MiniJson
import com.deepseek.dshrider.wire.clientRequestJson
import com.deepseek.dshrider.wire.clientResponseJson
import com.deepseek.dshrider.wire.parseServerResponse
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin HTTP client for the DeepSeek Harness Web GUI API.
 *
 * Two deliberately chosen transports, both verified against a live host:
 * - RPC calls (POST to the /api/&lt;method&gt; endpoints) use HttpURLConnection
 *   (the synchronous Socket stack). On some Windows machines the JDK
 *   HttpClient's async NIO stack gets RST by local loopback servers
 *   ("HTTP/1.1 header parser received no bytes"), while the classic stack
 *   works reliably.
 * - The event stream uses the JDK WebSocket client against /api/events.mux
 *   (the Web GUI's event transport is a WebSocket upgrade, not SSE).
 *
 * Runs on background threads only; callers marshal results to the EDT.
 */
class DshApi(baseUrl: String) {

    val normalizedBaseUrl: String = baseUrl.trim().trimEnd('/')

    private val wsClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /** POST /api/<method> with the client-request envelope; returns the parsed server-response result. */
    fun call(method: String, payloadJson: String, timeoutSeconds: Long = 120): DshRpcResult {
        val body = clientRequestJson(method, payloadJson)
        return try {
            val conn = open("$normalizedBaseUrl/api/$method", timeoutSeconds)
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            readResponse(conn)
        } catch (e: Exception) {
            DshRpcResult.fail("network", e.message ?: e.javaClass.simpleName)
        }
    }

    /** POST /api/respond with a client-response envelope. */
    fun respond(rpcId: String, valueJson: String, timeoutSeconds: Long = 30): DshRpcResult {
        val body = clientResponseJson(rpcId, valueJson)
        return try {
            val conn = open("$normalizedBaseUrl/api/respond", timeoutSeconds)
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val text = readAll(if (code >= 400) conn.errorStream else conn.inputStream)
            val root = MiniJson.parseOrNull(text)?.asObj()
            if (code in 200..299 && root != null && root.bool("accepted") == true) DshRpcResult.ok(null)
            else DshRpcResult.fail("respond-rejected", root?.str("reason", "unknown") ?: "HTTP $code")
        } catch (e: Exception) {
            DshRpcResult.fail("network", e.message ?: e.javaClass.simpleName)
        }
    }

    /** Cheap connectivity probe (session.list with a 5s budget, result discarded). */
    fun probe(): Boolean = call("session.list", "{}", timeoutSeconds = 5).ok

    /**
     * Open one WebSocket connection to /api/events.mux (the GUI event transport).
     * `onFrame` is invoked from the WebSocket receive thread for every parsed
     * frame (one JSON text message); `onDisconnect` is invoked at most once when
     * the socket closes or fails. Returns a handle that force-closes it.
     */
    fun openEventStream(
        onFrame: (DshFrame) -> Unit,
        onDisconnect: (Throwable?) -> Unit,
    ): DshEventStreamHandle {
        val closed = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val wsUrl = normalizedBaseUrl.replaceFirst(Regex("^http"), "ws") + "/api/events.mux"
        val socket = wsClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create(wsUrl), object : WebSocket.Listener {
                override fun onOpen(webSocket: WebSocket) {
                    webSocket.request(1)
                }

                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    if (!closed.get() && !finished.get()) {
                        val frame = DshFrame.parse(data.toString())
                        if (frame != null) onFrame(frame)
                    }
                    if (!closed.get() && !finished.get()) webSocket.request(1)
                    return null
                }

                override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                    if (finished.compareAndSet(false, true)) {
                        onDisconnect(IllegalStateException("ws closed $statusCode: $reason"))
                    }
                    return null
                }

                override fun onError(webSocket: WebSocket, error: Throwable) {
                    if (finished.compareAndSet(false, true)) onDisconnect(error)
                }
            }).join()
        return DshEventStreamHandle { closed.set(true); socket.abort() }
    }

    private fun open(url: String, timeoutSeconds: Long): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        conn.connectTimeout = 10_000
        conn.readTimeout = (timeoutSeconds * 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        conn.useCaches = false
        return conn
    }

    /** Business errors still arrive as HTTP 200; carrier failures use 4xx/5xx. */
    private fun readResponse(conn: HttpURLConnection): DshRpcResult {
        val code = conn.responseCode
        val text = readAll(if (code >= 400) conn.errorStream else conn.inputStream)
        if (code in 200..299) return parseServerResponse(text)
        val head = text.trim().take(160).replace('\n', ' ')
        return DshRpcResult.fail("network", "HTTP $code${if (head.isEmpty()) "" else ": $head"}")
    }

    private fun readAll(input: InputStream?): String {
        if (input == null) return ""
        return input.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}

/** Handle to an open SSE connection; close() is idempotent. */
class DshEventStreamHandle(private val closer: () -> Unit) {
    private val done = AtomicBoolean(false)
    fun close() {
        if (done.compareAndSet(false, true)) {
            try { closer() } catch (_: Exception) { /* best effort */ }
        }
    }
}
