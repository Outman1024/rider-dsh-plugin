package com.deepseek.dshrider.api

import com.deepseek.dshrider.wire.DshFrame
import com.deepseek.dshrider.wire.DshRpcResult
import com.deepseek.dshrider.wire.clientRequestJson
import com.deepseek.dshrider.wire.clientResponseJson
import com.deepseek.dshrider.wire.parseServerResponse
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin HTTP client for the DeepSeek Harness Web GUI API.
 * Runs on background threads only; callers marshal results to the EDT.
 */
class DshApi(baseUrl: String) {

    val normalizedBaseUrl: String = baseUrl.trim().trimEnd('/')

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** POST /api/<method> with the client-request envelope; returns the parsed server-response result. */
    fun call(method: String, payloadJson: String, timeoutSeconds: Long = 120): DshRpcResult {
        val body = clientRequestJson(method, payloadJson)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$normalizedBaseUrl/api/$method"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            parseServerResponse(response.body())
        } catch (e: Exception) {
            DshRpcResult.fail("network", e.message ?: e.javaClass.simpleName)
        }
    }

    /** POST /api/respond with a client-response envelope. */
    fun respond(rpcId: String, valueJson: String, timeoutSeconds: Long = 30): DshRpcResult {
        val body = clientResponseJson(rpcId, valueJson)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$normalizedBaseUrl/api/respond"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            val root = com.deepseek.dshrider.wire.MiniJson.parseOrNull(response.body())?.asObj()
            if (root != null && root.bool("accepted") == true) DshRpcResult.ok(null)
            else DshRpcResult.fail("respond-rejected", root?.str("reason", "unknown") ?: "unknown")
        } catch (e: Exception) {
            DshRpcResult.fail("network", e.message ?: e.javaClass.simpleName)
        }
    }

    /** Cheap connectivity probe (session.list with a 5s budget, result discarded). */
    fun probe(): Boolean = call("session.list", "{}", timeoutSeconds = 5).ok

    /**
     * Open one SSE connection to /api/events.mux.
     * `onFrame` is invoked from a private reader thread for every parsed frame
     * (one `data:` line); `onDisconnect` is invoked from the same thread when
     * the stream ends (always exactly once, then the connection is closed).
     * Returns a handle that force-closes the connection.
     */
    fun openEventStream(
        onFrame: (DshFrame) -> Unit,
        onDisconnect: (Throwable?) -> Unit,
    ): DshEventStreamHandle {
        val closed = AtomicBoolean(false)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$normalizedBaseUrl/api/events.mux"))
            .timeout(Duration.ofMinutes(30))
            .header("Accept", "text/event-stream")
            .GET()
            .build()
        val thread = Thread({
            var failure: Throwable? = null
            try {
                val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
                if (response.statusCode() != 200) {
                    failure = IllegalStateException("HTTP ${response.statusCode()} from events.mux")
                } else {
                    readSse(response.body()) { line ->
                        if (closed.get()) return@readSse false
                        if (line.startsWith("data:")) {
                            val data = line.removePrefix("data:").trim()
                            if (data.isNotEmpty()) {
                                val frame = DshFrame.parse(data)
                                if (frame != null) onFrame(frame)
                            }
                        }
                        true
                    }
                }
            } catch (e: Exception) {
                if (!closed.get()) failure = e
            } finally {
                if (!closed.get()) onDisconnect(failure)
            }
        }, "dsh-events-stream").apply {
            isDaemon = true
            start()
        }
        return DshEventStreamHandle { closed.set(true); thread.interrupt() }
    }

    /** Read an SSE body line by line until EOF; the callback returning false stops reading. */
    private fun readSse(input: InputStream, onLine: (String) -> Boolean) {
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        while (true) {
            val line = try { reader.readLine() } catch (_: Exception) { null } ?: break
            if (!onLine(line)) break
        }
        try { reader.close() } catch (_: Exception) { /* stream already gone */ }
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
