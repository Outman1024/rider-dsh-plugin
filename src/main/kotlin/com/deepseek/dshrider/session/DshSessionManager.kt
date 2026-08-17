package com.deepseek.dshrider.session

import com.deepseek.dshrider.api.DshApi
import com.deepseek.dshrider.settings.DshSettings
import com.deepseek.dshrider.settings.SessionPolicy
import com.deepseek.dshrider.wire.DshRpcResult
import com.deepseek.dshrider.wire.DshSessionSummary
import com.deepseek.dshrider.wire.JsonValue
import com.deepseek.dshrider.wire.MiniJson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import java.nio.file.Paths

/**
 * Resolves which harness session a prompt goes to, per the configured policy.
 * All methods are blocking and must run on a background thread.
 */
class DshSessionManager(private val project: Project, private val api: DshApi) {

    data class Resolved(val sessionId: String, val sessionCwd: String?, val summary: DshSessionSummary?)

    class ResolutionFailed(message: String) : Exception(message)

    /** List sessions; returns null when the host is unreachable or the call fails. */
    fun listSessions(): List<DshSessionSummary>? {
        val result = api.call("session.list", "{}", timeoutSeconds = 20)
        if (!result.ok) return null
        val value = result.value ?: return emptyList()
        return value.arr("items").mapNotNull { item ->
            val summary = DshSessionSummary.parse(item)
            if (summary.sessionId.isEmpty()) null else summary
        }
    }

    /**
     * Resolve the target session for the current project. Updates the persisted
     * project state on success. Falls back to a cwd-less create when the host
     * refuses the project directory.
     */
    fun resolveSession(settings: DshSettings, forceNew: Boolean = false): Result<Resolved> {
        val items = listSessions()
            ?: return Result.failure(ResolutionFailed("无法连接到 DeepSeek Harness（${api.normalizedBaseUrl}）。请确认已运行 dsh web。"))

        val projectCwd = project.guessProjectDir()?.canonicalPath?.takeIf { it.isNotEmpty() }
        val cached = DshProjectState.getInstance(project).sessionId

        when {
            forceNew -> return createForProject(settings, projectCwd)

            settings.sessionPolicy == SessionPolicy.PINNED -> {
                val pinned = settings.pinnedSessionId.trim()
                if (pinned.isEmpty()) return Result.failure(ResolutionFailed("Pinned policy selected but no session id configured"))
                val existing = items.firstOrNull { it.sessionId == pinned }
                if (existing != null) {
                    DshProjectState.getInstance(project).sessionId = pinned
                    return Result.success(Resolved(pinned, existing.cwd, existing))
                }
                val created = api.call("session.create", createPayload(projectCwd, pinned), 30)
                return handleCreate(created, settings, projectCwd)
            }

            settings.sessionPolicy == SessionPolicy.ALWAYS_NEW -> return createForProject(settings, projectCwd)

            settings.sessionPolicy == SessionPolicy.LAST_ACTIVE -> {
                val latest = items.filter { it.origin != "subagent" }.maxByOrNull { it.updatedAt }
                if (latest != null) {
                    DshProjectState.getInstance(project).sessionId = latest.sessionId
                    return Result.success(Resolved(latest.sessionId, latest.cwd, latest))
                }
                return createForProject(settings, projectCwd)
            }

            else -> { // PROJECT
                if (cached.isNotEmpty()) {
                    val stillThere = items.firstOrNull { it.sessionId == cached }
                    if (stillThere != null) return Result.success(Resolved(cached, stillThere.cwd, stillThere))
                }
                val byCwd = items.filter { it.origin != "subagent" && it.cwd == projectCwd }
                    .maxByOrNull { it.updatedAt }
                if (byCwd != null) {
                    DshProjectState.getInstance(project).sessionId = byCwd.sessionId
                    return Result.success(Resolved(byCwd.sessionId, byCwd.cwd, byCwd))
                }
                return createForProject(settings, projectCwd)
            }
        }
    }

    private fun createForProject(settings: DshSettings, projectCwd: String?): Result<Resolved> {
        val result = api.call("session.create", createPayload(projectCwd, null), 30)
        return handleCreate(result, settings, projectCwd)
    }

    private fun handleCreate(result: DshRpcResult, settings: DshSettings, projectCwd: String?): Result<Resolved> {
        if (result.ok) {
            val sessionId = result.value?.asObj()?.str("sessionId") ?: ""
            if (sessionId.isEmpty()) return Result.failure(ResolutionFailed("session.create returned no sessionId"))
            DshProjectState.getInstance(project).sessionId = sessionId
            val summary = listSessions()?.firstOrNull { it.sessionId == sessionId }
            return Result.success(Resolved(sessionId, summary?.cwd ?: projectCwd, summary))
        }
        // Host refused the cwd (sandbox policy / unreadable directory): retry once without it.
        if (projectCwd != null && result.error?.code in setOf("directory-unreadable", "directory-create-failed", "bad-request", "internal")) {
            val retry = api.call("session.create", createPayload(null, null), 30)
            if (retry.ok) {
                val sessionId = retry.value?.asObj()?.str("sessionId") ?: ""
                if (sessionId.isNotEmpty()) {
                    DshProjectState.getInstance(project).sessionId = sessionId
                    return Result.success(Resolved(sessionId, null, null))
                }
            }
        }
        return Result.failure(ResolutionFailed(result.error?.message ?: "session.create failed"))
    }

    private fun createPayload(cwd: String?, pinnedSessionId: String?): String {
        val fields = ArrayList<String>()
        cwd?.takeIf { it.isNotEmpty() }?.let { fields += "\"cwd\":${MiniJson.quoted(it)}" }
        pinnedSessionId?.let { fields += "\"sessionId\":${MiniJson.quoted(it)}" }
        return "{${fields.joinToString(",")}}"
    }
}
