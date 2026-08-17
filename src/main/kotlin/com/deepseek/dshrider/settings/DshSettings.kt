package com.deepseek.dshrider.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

enum class SessionPolicy(val label: String) {
    PROJECT("Per project (session cwd = project root)"),
    LAST_ACTIVE("Most recently active session"),
    ALWAYS_NEW("New session every time"),
    PINNED("Pinned session id"),
}

@State(name = "DshSettings", storages = [Storage("dsh-rider.xml")])
@Service(Service.Level.APP)
class DshSettings : PersistentStateComponent<DshSettings> {

    var baseUrl: String = "http://127.0.0.1:3080"

    var sessionPolicyName: String = SessionPolicy.PROJECT.name

    var pinnedSessionId: String = ""

    /** Include whole file content when asking about a file (truncated at maxCodeChars). */
    var includeFileContent: Boolean = false

    /** Cap (chars) for any code/file content attached to a prompt. */
    var maxCodeChars: Int = 20000

    /** Default question shown in the ask dialog. */
    var defaultQuestion: String = "请分析以上代码：说明它做了什么，指出潜在问题，并给出改进建议。"

    /** Follow-up prompt sent when no question text is given at all. */
    var fallbackQuestion: String = "请查看以上内容。"

    /** Show the reasoning stream (思考过程) in the tool window. */
    var showReasoning: Boolean = true

    /** Open the Web GUI in the browser right after a send. */
    var autoOpenWeb: Boolean = false

    val sessionPolicy: SessionPolicy
        get() = try { SessionPolicy.valueOf(sessionPolicyName) } catch (_: Exception) { SessionPolicy.PROJECT }

    override fun getState(): DshSettings = this

    override fun loadState(state: DshSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): DshSettings = ApplicationManager.getApplication().getService(DshSettings::class.java)
    }
}
