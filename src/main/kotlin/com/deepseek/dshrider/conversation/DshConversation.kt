package com.deepseek.dshrider.conversation

import com.deepseek.dshrider.wire.DshFrame
import com.deepseek.dshrider.wire.DshQuestion
import com.deepseek.dshrider.wire.DshSessionEvent
import com.deepseek.dshrider.wire.JsonValue
import java.util.concurrent.CopyOnWriteArrayList

/** Immutable-ish snapshot row rendered by the tool window. */
sealed class Entry {
    abstract val id: String
    // NOTE: fully qualified java.lang.System — the nested `System` entry class would shadow it.
    var timeMillis: Long = java.lang.System.currentTimeMillis()

    class User(val text: String, val contextLabel: String?) : Entry() {
        override val id: String = "u-${java.lang.System.nanoTime()}"
    }

    class Assistant : Entry() {
        override val id: String = "a-${java.lang.System.nanoTime()}"
        @Volatile var text: String = ""
        @Volatile var reasoning: String = ""
        @Volatile var finalized: Boolean = false
        @Volatile var status: String = ""
    }

    class Tool(val name: String) : Entry() {
        override val id: String = "t-${java.lang.System.nanoTime()}"
        @Volatile var status: String = "running"
    }

    class System(val text: String, val kind: String = "info") : Entry() {
        override val id: String = "s-${java.lang.System.nanoTime()}"
    }

    class Approval(
        val frameRpcId: String,
        val sessionId: String,
        val approvalId: String,
        val toolName: String,
        val reason: String?,
    ) : Entry() {
        override val id: String = "ap-$approvalId"
        @Volatile var state: String = "pending" // pending | allowed-once | rejected | cancelled | unavailable
    }

    class QuestionEntry(val frameRpcId: String, val sessionId: String, val questions: List<DshQuestion>) : Entry() {
        override val id: String = "q-$frameRpcId"
        @Volatile var state: String = "pending" // pending | answered | cancelled
    }
}

/**
 * Mutable transcript model. Safe to call from the event thread: mutation is
 * synchronized and listeners are notified under the lock; listeners must only
 * read snapshots and marshal UI work themselves.
 */
class DshConversation(private val sessionId: String) {

    interface Listener {
        fun onEntryAdded(index: Int, entry: Entry)
        fun onEntryChanged(index: Int, entry: Entry)
        fun onStatus(text: String, connected: Boolean)
    }

    private val entries = CopyOnWriteArrayList<Entry>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val lock = Any()

    @Volatile var lastAppliedSeq: Long = -1

    var statusText: String = "连接中…"
        private set
    var connected: Boolean = false
        private set

    fun addListener(listener: Listener) { listeners.add(listener) }

    fun removeListener(listener: Listener) { listeners.remove(listener) }

    fun snapshot(): List<Entry> = entries.toList()

    fun setStatus(text: String, isConnected: Boolean) {
        synchronized(lock) {
            statusText = text
            connected = isConnected
        }
        listeners.forEach { it.onStatus(statusText, connected) }
    }

    fun addUser(text: String, contextLabel: String?) {
        val entry = Entry.User(text, contextLabel)
        val index = append(entry)
        listeners.forEach { it.onEntryAdded(index, entry) }
    }

    fun addSystem(text: String, kind: String = "info") {
        val entry = Entry.System(text, kind)
        val index = append(entry)
        listeners.forEach { it.onEntryAdded(index, entry) }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /** Apply one live mux frame for this session (or a global frame). */
    fun applyFrame(frame: DshFrame) {
        when (frame) {
            is DshFrame.SessionEventFrame -> {
                if (frame.sessionId != sessionId) return
                applySessionEvent(frame.event)
            }
            is DshFrame.ApprovalRequested -> {
                if (frame.sessionId != sessionId) return
                val entry = Entry.Approval(frame.rpcId, frame.sessionId, frame.approvalId, frame.toolName, frame.reason)
                val index = append(entry)
                listeners.forEach { it.onEntryAdded(index, entry) }
            }
            is DshFrame.ApprovalResolved -> {
                if (frame.sessionId != sessionId) return
                val entry = entries.firstOrNull { it is Entry.Approval && it.approvalId == frame.approvalId }
                if (entry is Entry.Approval) {
                    entry.state = frame.outcome
                    notifyChanged(entry)
                }
            }
            is DshFrame.QuestionRequested -> {
                if (frame.sessionId != sessionId) return
                val entry = Entry.QuestionEntry(frame.rpcId, frame.sessionId, frame.questions)
                val index = append(entry)
                listeners.forEach { it.onEntryAdded(index, entry) }
            }
            is DshFrame.QuestionResolved -> {
                val entry = entries.firstOrNull { it is Entry.QuestionEntry && it.frameRpcId == frame.rpcId }
                if (entry is Entry.QuestionEntry) {
                    entry.state = frame.outcome
                    notifyChanged(entry)
                }
            }
            is DshFrame.StreamError -> {
                val entry = Entry.System("流错误: ${frame.message.ifBlank { frame.code }}", "error")
                val index = append(entry)
                listeners.forEach { it.onEntryAdded(index, entry) }
            }
            is DshFrame.SessionSubscribed -> {
                if (frame.sessionId == sessionId) {
                    lastAppliedSeq = maxOf(lastAppliedSeq, frame.lastSeq)
                }
            }
            else -> Unit
        }
    }

    /** Replay one history event (reconnect catch-up); seq-gated. */
    fun applyHistoryEvent(event: DshSessionEvent) {
        if (event.seq <= lastAppliedSeq) return
        lastAppliedSeq = event.seq
        applySessionEvent(event)
    }

    fun recordLocalSeq(seq: Long) {
        synchronized(lock) { lastAppliedSeq = maxOf(lastAppliedSeq, seq) }
    }

    private fun applySessionEvent(event: DshSessionEvent) {
        if (event.seq >= 0 && event.seq <= lastAppliedSeq) return
        if (event.seq >= 0) lastAppliedSeq = event.seq
        when (event.type) {
            "assistant/chunk" -> applyChunk(event.data)
            "assistant/message" -> applyAssistantMessage(event.data)
            "assistant/text", "assistant/delta" -> appendAssistantText(extractText(event.data))
            "turn/start" -> {
                val current = entries.lastOrNull { it is Entry.Assistant && !it.finalized }
                if (current == null) {
                    val entry = Entry.Assistant()
                    entry.status = "正在思考…"
                    val index = append(entry)
                    listeners.forEach { it.onEntryAdded(index, entry) }
                }
            }
            "turn/end", "step/end" -> {
                entries.lastOrNull { it is Entry.Assistant && !it.finalized }?.let {
                    (it as Entry.Assistant).finalized = true
                    it.status = ""
                    notifyChanged(it)
                }
            }
            "tool/call", "tool/start" -> {
                val name = firstNonEmpty(event.data.str("name"), event.data.str("toolName"), event.data.str("tool"), event.data.str("callId"))
                val entry = Entry.Tool(name.ifBlank { "工具调用" })
                val index = append(entry)
                listeners.forEach { it.onEntryAdded(index, entry) }
            }
            "tool/result", "tool/end" -> {
                val entry = entries.lastOrNull { it is Entry.Tool && it.status == "running" }
                if (entry is Entry.Tool) {
                    val status = firstNonEmpty(
                        event.data.str("status"),
                        event.data.bool("ok").let { ok -> if (ok) "ok" else "" },
                    )
                    entry.status = if (status.isNotEmpty()) status else "done"
                    notifyChanged(entry)
                }
            }
            "user/message", "user/prompt" -> {
                val text = firstNonEmpty(extractText(event.data), extractContentText(event.data))
                if (text.isNotEmpty()) {
                    val duplicate = entries.lastOrNull { it is Entry.User && it.text == text }
                    if (duplicate == null) {
                        val entry = Entry.User(text, null)
                        val index = append(entry)
                        listeners.forEach { it.onEntryAdded(index, entry) }
                    }
                }
            }
            "assistant/error", "agent/error" -> {
                val text = extractText(event.data)
                if (text.isNotEmpty()) {
                    val entry = Entry.System(text, "error")
                    val index = append(entry)
                    listeners.forEach { it.onEntryAdded(index, entry) }
                }
            }
            else -> Unit
        }
    }

    private fun applyChunk(data: JsonValue) {
        val chunk = data.objField("chunk") ?: data
        val chunkType = chunk.str("type")
        when {
            chunkType == "text-delta" || (chunkType.isEmpty() && chunk.str("text").isNotEmpty()) ->
                appendAssistantText(chunk.str("text"))
            chunkType == "reasoning-delta" -> appendReasoning(chunk.str("text"))
            chunkType == "block-start" -> {
                val blockType = chunk.str("blockType", "text")
                if (blockType != "text") {
                    // A non-text block starts; close the current text stream so a
                    // following text-delta opens fresh.
                    entries.lastOrNull { it is Entry.Assistant && !it.finalized }?.let {
                        (it as Entry.Assistant).finalized = true
                        it.status = ""
                        notifyChanged(it)
                    }
                }
            }
            else -> {
                val text = chunk.str("text")
                if (text.isNotEmpty()) appendAssistantText(text)
            }
        }
    }

    private fun applyAssistantMessage(data: JsonValue) {
        val finalText = extractContentText(data)
        val current = entries.lastOrNull { it is Entry.Assistant && !it.finalized }
        if (current is Entry.Assistant) {
            if (finalText.isNotEmpty()) current.text = finalText
            current.finalized = true
            current.status = ""
            notifyChanged(current)
        } else if (finalText.isNotEmpty()) {
            val entry = Entry.Assistant()
            entry.text = finalText
            entry.finalized = true
            val index = append(entry)
            listeners.forEach { it.onEntryAdded(index, entry) }
        }
    }

    private fun appendAssistantText(text: String) {
        if (text.isEmpty()) return
        val current = entries.lastOrNull { it is Entry.Assistant && !it.finalized }
        if (current is Entry.Assistant) {
            current.text += text
            if (current.status.isNotEmpty()) current.status = ""
            notifyChanged(current)
        } else {
            val entry = Entry.Assistant()
            entry.text = text
            val index = append(entry)
            listeners.forEach { it.onEntryAdded(index, entry) }
        }
    }

    private fun appendReasoning(text: String) {
        if (text.isEmpty()) return
        val current = entries.lastOrNull { it is Entry.Assistant && !it.finalized }
        if (current is Entry.Assistant) {
            current.reasoning += text
            notifyChanged(current)
        }
    }

    private fun notifyChanged(entry: Entry) {
        val index = entries.indexOf(entry)
        if (index >= 0) listeners.forEach { it.onEntryChanged(index, entry) }
    }

    private fun append(entry: Entry): Int {
        synchronized(lock) {
            entries.add(entry)
            return entries.size - 1
        }
    }

    /** Extract text from a plain {text} field. */
    private fun extractText(data: JsonValue): String =
        data.obj("text")?.asStr() ?: data.obj("message")?.asStr() ?: ""

    /** Extract concatenated text blocks from a {content:[{type:'text',text}]} shape. */
    private fun extractContentText(data: JsonValue): String {
        val content = data.objField("content") ?: return extractText(data)
        val sb = StringBuilder()
        for (block in content.arr("content")) {
            if (block.asObj()?.str("type") == "text") {
                val text = block.asObj()?.str("text") ?: ""
                if (text.isNotEmpty()) sb.append(text)
            }
        }
        return sb.toString()
    }

    private fun firstNonEmpty(vararg values: String): String = values.firstOrNull { it.isNotBlank() } ?: ""
}
