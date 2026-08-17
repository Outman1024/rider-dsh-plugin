package com.deepseek.dshrider.stream

import com.deepseek.dshrider.api.DshApi
import com.deepseek.dshrider.api.DshEventStreamHandle
import com.deepseek.dshrider.conversation.DshConversation
import com.deepseek.dshrider.session.DshProjectState
import com.deepseek.dshrider.session.DshSessionManager
import com.deepseek.dshrider.settings.DshSettings
import com.deepseek.dshrider.wire.DshSessionEvent
import com.deepseek.dshrider.wire.JsonValue
import com.deepseek.dshrider.wire.MiniJson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.Disposable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Project-level runtime: owns the DshApi, the conversation model, the SSE
 * connection with reconnect + history catch-up, and the send/respond flows.
 */
@Service(Service.Level.PROJECT)
class DshRuntime(private val project: Project) : Disposable {

    interface Listener {
        fun onConversationChanged(conversation: DshConversation)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    private val apiRef = AtomicReference<DshApi?>()
    private val streamHandle = AtomicReference<DshEventStreamHandle?>()
    private val conversationRef = AtomicReference<DshConversation?>()
    private val disposed = AtomicBoolean(false)
    private val reconnectDelayMs = AtomicReference(2000L)

    private val backgroundExecutor = ApplicationManager.getApplication()

    val conversation: DshConversation?
        get() = conversationRef.get()

    fun addListener(listener: Listener) { listeners.add(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    /** API instance rebuilt whenever the configured base URL changes. */
    fun api(): DshApi {
        val settings = DshSettings.getInstance()
        val existing = apiRef.get()
        if (existing != null && existing.normalizedBaseUrl == settings.baseUrl) return existing
        val fresh = DshApi(settings.baseUrl)
        apiRef.set(fresh)
        return fresh
    }

    fun sessionManager(): DshSessionManager = DshSessionManager(project, api())

    fun ensureConversation(): DshConversation {
        conversationRef.get()?.let { return it }
        val sessionId = DshProjectState.getInstance(project).sessionId
        val conv = DshConversation(sessionId.ifBlank { "pending" })
        conv.lastAppliedSeq = DshProjectState.getInstance(project).lastSeq
        conversationRef.set(conv)
        listeners.forEach { it.onConversationChanged(conv) }
        ensureStream()
        return conv
    }

    fun ensureStream() {
        val current = streamHandle.get()
        if (current != null) return
        connectStream()
    }

    private fun connectStream() {
        if (disposed.get() || streamHandle.get() != null) return
        // The WebSocket handshake blocks its caller; never do it on the EDT.
        backgroundExecutor.executeOnPooledThread {
            if (disposed.get() || streamHandle.get() != null) return@executeOnPooledThread
            try {
                val api = api()
                val handle = api.openEventStream(
                    onFrame = { frame -> routeFrame(frame) },
                    onDisconnect = { failure ->
                        conversationRef.get()?.let { conv ->
                            ApplicationManager.getApplication().invokeLater {
                                conv.setStatus(if (failure == null) "已断开，正在重连…" else "连接断开，正在重连…", false)
                            }
                        }
                        scheduleReconnect()
                    },
                )
                if (disposed.get()) {
                    handle.close()
                    return@executeOnPooledThread
                }
                streamHandle.set(handle)
                reconnectDelayMs.set(2000L)
                conversationRef.get()?.let { conv ->
                    ApplicationManager.getApplication().invokeLater { conv.setStatus("已连接", true) }
                }
            } catch (e: Exception) {
                // Handshake failed: report and fall into the reconnect cycle.
                conversationRef.get()?.let { conv ->
                    ApplicationManager.getApplication().invokeLater {
                        conv.setStatus("连接失败，正在重试…", false)
                    }
                }
                scheduleReconnect()
            }
        }
    }

    private fun routeFrame(frame: com.deepseek.dshrider.wire.DshFrame) {
        val conv = conversationRef.get() ?: return
        when (frame) {
            is com.deepseek.dshrider.wire.DshFrame.SessionEventFrame -> {
                if (frame.sessionId != currentSessionId()) return
                if (frame.event.seq >= 0) {
                    DshProjectState.getInstance(project).lastSeq = maxOf(
                        DshProjectState.getInstance(project).lastSeq, frame.event.seq,
                    )
                }
                ApplicationManager.getApplication().invokeLater { conv.applyFrame(frame) }
            }
            else -> ApplicationManager.getApplication().invokeLater { conv.applyFrame(frame) }
        }
    }

    private fun currentSessionId(): String = DshProjectState.getInstance(project).sessionId

    private fun scheduleReconnect() {
        streamHandle.set(null)
        if (disposed.get()) return
        val delay = reconnectDelayMs.get()
        reconnectDelayMs.set((delay * 2).coerceAtMost(30_000L))
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "dsh-reconnect").apply { isDaemon = true }
        }.schedule({
            if (disposed.get()) return@schedule
            reconnectDelayMs.set(2000L)
            connectStream()
            catchUpHistory()
        }, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Pull session.history and replay events newer than lastAppliedSeq. */
    fun catchUpHistory() {
        val sessionId = currentSessionId()
        if (sessionId.isEmpty()) return
        val conv = conversationRef.get() ?: return
        backgroundExecutor.executeOnPooledThread {
            val result = api().call(
                "session.history",
                """{"sessionId":${MiniJson.quoted(sessionId)},"maxMessages":200}""",
                timeoutSeconds = 30,
            )
            if (!result.ok) return@executeOnPooledThread
            val events = result.value?.arr("events") ?: return@executeOnPooledThread
            val parsed = ArrayList<DshSessionEvent>()
            for (item in events) {
                val eventObj = item.objField("event") ?: continue
                val type = eventObj.str("type")
                val seq = eventObj.long("seq")
                if (type.isEmpty() || seq < 0) continue
                if (seq <= conv.lastAppliedSeq) continue
                parsed += DshSessionEvent(type, seq, eventObj.obj("data") ?: JsonValue.Obj(LinkedHashMap()))
            }
            parsed.sortedBy { it.seq }.forEach { event ->
                ApplicationManager.getApplication().invokeLater {
                    conv.applyHistoryEvent(event)
                    DshProjectState.getInstance(project).lastSeq =
                        maxOf(DshProjectState.getInstance(project).lastSeq, event.seq)
                }
            }
        }
    }

    /** Switch the conversation to a (newly resolved) session. */
    fun switchSession(sessionId: String) {
        val conv = DshConversation(sessionId)
        conv.lastAppliedSeq = DshProjectState.getInstance(project).lastSeq
        conversationRef.set(conv)
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it.onConversationChanged(conv) }
            conv.setStatus("已连接", true)
        }
        ensureStream()
        catchUpHistory()
    }

    /**
     * Resolve the target session (creating it when needed) and send one prompt.
     * Runs on a pooled thread; `onDone` reports a user-facing failure message or null.
     */
    fun sendPrompt(messageText: String, contextLabel: String?, onDone: (String?) -> Unit) {
        val conv = ensureConversation()
        conv.setStatus("正在连接…", conv.connected)
        backgroundExecutor.executeOnPooledThread {
            val settings = DshSettings.getInstance()
            val resolved = sessionManager().resolveSession(settings, forceNew = false)
            val sessionId = resolved.getOrNull()?.sessionId
            if (sessionId == null) {
                val message = resolved.exceptionOrNull()?.message ?: "无法解析目标会话"
                reportFailure(conv, message, onDone)
                return@executeOnPooledThread
            }
            if (sessionId != convSessionId(conv)) {
                DshProjectState.getInstance(project).sessionId = sessionId
                switchSession(sessionId)
            }
            val promptConv = conversationRef.get() ?: conv
            val payload = """{"sessionId":${MiniJson.quoted(sessionId)},"mode":"queue","""
                .plus("\"content\":[{\"type\":\"text\",\"text\":").plus(MiniJson.quoted(messageText)).plus("}]}")
            val result = api().call("session.prompt", payload, timeoutSeconds = 60)
            if (result.ok) {
                ApplicationManager.getApplication().invokeLater {
                    promptConv.addUser(messageText, contextLabel)
                    promptConv.setStatus("已发送，等待回复…", true)
                }
                if (settings.autoOpenWeb) openWeb()
                onDone(null)
                return@executeOnPooledThread
            }
            val code = result.error?.code ?: "internal"
            val message = result.error?.message ?: "session.prompt failed"
            when (code) {
                "agent-busy" -> {
                    reportFailure(promptConv, "上一个回合仍在运行中，请等它完成后再发送。", onDone)
                }
                "session-not-found" -> {
                    // Cached session was archived/removed: drop it and retry once with a fresh one.
                    DshProjectState.getInstance(project).sessionId = ""
                    val retry = sessionManager().resolveSession(settings, forceNew = true)
                    val freshId = retry.getOrNull()?.sessionId
                    if (freshId != null) {
                        DshProjectState.getInstance(project).sessionId = freshId
                        switchSession(freshId)
                        val freshConv = conversationRef.get() ?: promptConv
                        val freshPayload = """{"sessionId":${MiniJson.quoted(freshId)},"mode":"queue","""
                            .plus("\"content\":[{\"type\":\"text\",\"text\":").plus(MiniJson.quoted(messageText)).plus("}]}")
                        val second = api().call("session.prompt", freshPayload, timeoutSeconds = 60)
                        if (second.ok) {
                            ApplicationManager.getApplication().invokeLater {
                                freshConv.addUser(messageText, contextLabel)
                                freshConv.setStatus("已发送，等待回复…", true)
                            }
                            if (settings.autoOpenWeb) openWeb()
                            onDone(null)
                            return@executeOnPooledThread
                        }
                    }
                    reportFailure(promptConv, "发送失败: $message", onDone)
                }
                "network" -> reportFailure(promptConv, "无法连接到 DeepSeek Harness（${api().normalizedBaseUrl}）。请确认已运行 dsh web。", onDone)
                else -> reportFailure(promptConv, "发送失败: $message", onDone)
            }
        }
    }

    /** Start a fresh session for this project and switch the conversation to it. */
    fun newSession(onDone: (String?) -> Unit) {
        ensureConversation()
        backgroundExecutor.executeOnPooledThread {
            val settings = DshSettings.getInstance()
            val resolved = sessionManager().resolveSession(settings, forceNew = true)
            val sessionId = resolved.getOrNull()?.sessionId
            if (sessionId == null) {
                val message = resolved.exceptionOrNull()?.message ?: "创建新会话失败"
                conversationRef.get()?.addSystem("创建新会话失败: $message", "error")
                onDone(message)
                return@executeOnPooledThread
            }
            DshProjectState.getInstance(project).sessionId = sessionId
            DshProjectState.getInstance(project).lastSeq = -1
            switchSession(sessionId)
            onDone(null)
        }
    }

    /** Answer an approval card: allowed-once or rejected. */
    fun respondApproval(
        entry: com.deepseek.dshrider.conversation.Entry.Approval,
        outcome: String,
        onDone: (String?) -> Unit,
    ) {
        backgroundExecutor.executeOnPooledThread {
            val value = """{"approvalId":${MiniJson.quoted(entry.approvalId)},"""
                .plus("\"sessionId\":${MiniJson.quoted(entry.sessionId)},")
                .plus("\"outcome\":${MiniJson.quoted(outcome)}}")
            val result = api().respond(entry.frameRpcId, value)
            onDone(if (result.ok) null else result.error?.message ?: "respond failed")
        }
    }

    /** Answer a question card with per-question selected labels. */
    fun answerQuestions(
        entry: com.deepseek.dshrider.conversation.Entry.QuestionEntry,
        selectedByQuestion: Map<String, List<String>>,
        onDone: (String?) -> Unit,
    ) {
        backgroundExecutor.executeOnPooledThread {
            val answers = entry.questions.joinToString(",") { q ->
                val selected = selectedByQuestion[q.id].orEmpty()
                """{"id":${MiniJson.quoted(q.id)},"selected":[${selected.joinToString(",") { MiniJson.quoted(it) }}]}"""
            }
            val value = """{"sessionId":${MiniJson.quoted(entry.sessionId)},"""
                .plus("\"answer\":{\"answers\":[$answers]}}")
            val result = api().respond(entry.frameRpcId, value)
            onDone(if (result.ok) null else result.error?.message ?: "respond failed")
        }
    }

    fun openWeb() {
        val settings = DshSettings.getInstance()
        try {
            com.intellij.ide.BrowserUtil.browse(settings.baseUrl)
        } catch (_: Exception) {
            conversationRef.get()?.addSystem("无法打开浏览器: ${settings.baseUrl}", "error")
        }
    }

    private fun convSessionId(conv: DshConversation): String =
        DshProjectState.getInstance(project).sessionId

    private fun reportFailure(conv: DshConversation, message: String, onDone: (String?) -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            conv.addSystem(message, "error")
            conv.setStatus("发送失败", false)
        }
        onDone(message)
    }

    override fun dispose() {
        disposed.set(true)
        streamHandle.getAndSet(null)?.close()
    }
}
