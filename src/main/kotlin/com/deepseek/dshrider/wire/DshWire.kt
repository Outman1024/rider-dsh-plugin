package com.deepseek.dshrider.wire

import java.util.UUID

/**
 * Wire data structures for the DeepSeek Harness HTTP API.
 *
 * The Web GUI host exposes POST /api/<method> with a client-request envelope:
 *   { "type": "client-request", "rpcId": "<uuid>", "method": "<method>", "payload": { ... } }
 * and answers 200 with a server-response envelope whose result is
 *   { "ok": true, "value": {...} } or { "ok": false, "error": { "code", "message", "details" } }.
 *
 * Events arrive on GET /api/events.mux as an SSE stream; every data line is a
 * server-request envelope whose payload is a mux frame (see DshFrame.parse).
 */

data class DshRpcError(val code: String, val message: String)

class DshRpcResult private constructor(
    val ok: Boolean,
    val value: JsonValue?,
    val error: DshRpcError?,
) {
    companion object {
        fun ok(value: JsonValue?): DshRpcResult = DshRpcResult(true, value, null)
        fun fail(error: DshRpcError): DshRpcResult = DshRpcResult(false, null, error)
        fun fail(code: String, message: String): DshRpcResult = DshRpcResult(false, null, DshRpcError(code, message))
    }
}

/** Build a client-request envelope body. `payloadJson` must be a JSON object literal. */
fun clientRequestJson(method: String, payloadJson: String): String =
    """{"type":"client-request","rpcId":${MiniJson.quoted(UUID.randomUUID().toString())},"""
        .plus(MiniJson.quoted("method")).plus(":").plus(MiniJson.quoted(method)).plus(",\"payload\":")
        .plus(payloadJson).plus("}")

/** Build a client-response envelope body (used for POST /api/respond). */
fun clientResponseJson(rpcId: String, valueJson: String): String =
    """{"type":"client-response","rpcId":${MiniJson.quoted(rpcId)},"result":{"ok":true,"value":"""
        .plus(valueJson).plus("}}")

/** Parse a server-response envelope into a DshRpcResult. */
fun parseServerResponse(body: String?): DshRpcResult {
    val root = MiniJson.parseOrNull(body)?.asObj()
        ?: return DshRpcResult.fail("internal", "unreadable server response")
    val result = root.objField("result") ?: return DshRpcResult.fail("internal", "response has no result field")
    if (result.bool("ok") == true) return DshRpcResult.ok(result.obj("value"))
    val error = result.objField("error")
    return DshRpcResult.fail(
        error?.str("code", "internal") ?: "internal",
        error?.str("message", "unknown error") ?: "unknown error",
    )
}

// ---------------------------------------------------------------------------
// Session summaries (session.list)
// ---------------------------------------------------------------------------

data class DshSessionSummary(
    val sessionId: String,
    val updatedAt: Long,
    val running: Boolean,
    val blank: Boolean,
    val parentSessionId: String?,
    val origin: String?,
    val cwd: String?,
    val agentPreset: String?,
) {
    companion object {
        fun parse(value: JsonValue): DshSessionSummary {
            val o = value.asObj() ?: JsonValue.Obj(LinkedHashMap())
            return DshSessionSummary(
                sessionId = o.str("sessionId"),
                updatedAt = o.long("updatedAt"),
                running = o.bool("running"),
                blank = o.bool("blank"),
                parentSessionId = o.obj("parentSessionId")?.asStr(),
                origin = o.obj("origin")?.asStr(),
                cwd = o.obj("cwd")?.asStr(),
                agentPreset = o.obj("agentPreset")?.asStr(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Session events (the `event` slot of a session/event frame, and of history entries)
// ---------------------------------------------------------------------------

data class DshSessionEvent(
    val type: String,
    val seq: Long,
    val data: JsonValue,
)

// ---------------------------------------------------------------------------
// Mux frames (payload of an SSE server-request envelope)
// ---------------------------------------------------------------------------

sealed class DshFrame {
    /** Outer envelope rpcId — echoed back on POST /api/respond for answerable frames. */
    abstract val rpcId: String

    data class SessionEventFrame(
        override val rpcId: String,
        val sessionId: String,
        val event: DshSessionEvent,
    ) : DshFrame()

    data class ApprovalRequested(
        override val rpcId: String,
        val sessionId: String,
        val approvalId: String,
        val toolName: String,
        val callId: String?,
        val reason: String?,
    ) : DshFrame()

    data class ApprovalResolved(
        override val rpcId: String,
        val sessionId: String,
        val approvalId: String,
        val outcome: String,
    ) : DshFrame()

    data class QuestionRequested(
        override val rpcId: String,
        val sessionId: String,
        val questions: List<DshQuestion>,
    ) : DshFrame()

    data class QuestionResolved(
        override val rpcId: String,
        val sessionId: String,
        val outcome: String,
    ) : DshFrame()

    data class SessionSubscribed(
        override val rpcId: String,
        val sessionId: String,
        val lastSeq: Long,
    ) : DshFrame()

    data class StreamError(override val rpcId: String, val code: String, val message: String) : DshFrame()

    data class Unknown(override val rpcId: String, val payloadType: String) : DshFrame()

    companion object {
        /** Parse one SSE `data:` line. Returns null when the line is not a server-request frame. */
        fun parse(dataLine: String): DshFrame? {
            val root = MiniJson.parseOrNull(dataLine)?.asObj() ?: return null
            if (root.str("type") != "server-request") return null
            val rpcId = root.str("rpcId")
            val payload = root.objField("payload") ?: return null
            val payloadType = payload.str("type")
            return when (payloadType) {
                "session/event" -> {
                    val eventObj = payload.objField("event") ?: return DshFrame.Unknown(rpcId, payloadType)
                    SessionEventFrame(
                        rpcId = rpcId,
                        sessionId = payload.str("sessionId"),
                        event = DshSessionEvent(
                            type = eventObj.str("type", "unknown/event"),
                            seq = eventObj.long("seq"),
                            data = eventObj.obj("data") ?: JsonValue.Obj(LinkedHashMap()),
                        ),
                    )
                }
                "approval/requested" -> ApprovalRequested(
                    rpcId = rpcId,
                    sessionId = payload.str("sessionId"),
                    approvalId = payload.str("approvalId"),
                    toolName = payload.str("toolName"),
                    callId = payload.obj("callId")?.asStr(),
                    reason = payload.obj("reason")?.asStr(),
                )
                "approval/resolved" -> ApprovalResolved(
                    rpcId = rpcId,
                    sessionId = payload.str("sessionId"),
                    approvalId = payload.str("approvalId"),
                    outcome = payload.str("outcome"),
                )
                "question/requested" -> QuestionRequested(
                    rpcId = rpcId,
                    sessionId = payload.str("sessionId"),
                    questions = payload.arr("questions").mapNotNull(DshQuestion::parse),
                )
                "question/resolved" -> QuestionResolved(
                    rpcId = rpcId,
                    sessionId = payload.str("sessionId"),
                    outcome = payload.str("outcome"),
                )
                "session/subscribed" -> SessionSubscribed(
                    rpcId = rpcId,
                    sessionId = payload.str("sessionId"),
                    lastSeq = payload.long("lastSeq"),
                )
                "stream/error" -> {
                    val error = payload.objField("error")
                    StreamError(
                        rpcId = rpcId,
                        code = error?.str("code", "internal") ?: "internal",
                        message = error?.str("message", "") ?: "",
                    )
                }
                else -> Unknown(rpcId, payloadType)
            }
        }
    }
}

data class DshQuestion(
    val id: String,
    val question: String,
    val header: String?,
    val options: List<DshQuestionOption>,
    val multiSelect: Boolean,
    val planApprove: String?,
) {
    data class DshQuestionOption(val label: String, val description: String?)

    companion object {
        fun parse(value: JsonValue): DshQuestion? {
            val o = value.asObj() ?: return null
            val id = o.str("id")
            if (id.isEmpty()) return null
            var planApprove: String? = null
            val intent = o.objField("intent")
            if (intent != null && intent.str("kind") == "plan-review") {
                planApprove = intent.obj("approve")?.asStr()
            }
            return DshQuestion(
                id = id,
                question = o.str("question"),
                header = o.obj("header")?.asStr(),
                options = o.arr("options").mapNotNull { opt ->
                    val oo = opt.asObj() ?: return@mapNotNull null
                    val label = oo.str("label")
                    if (label.isEmpty()) null else DshQuestionOption(label, oo.obj("description")?.asStr())
                },
                multiSelect = o.bool("multiSelect"),
                planApprove = planApprove,
            )
        }
    }
}
