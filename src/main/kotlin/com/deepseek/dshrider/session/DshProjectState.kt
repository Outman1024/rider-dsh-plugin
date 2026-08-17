package com.deepseek.dshrider.session

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/** Per-project persisted plugin state (survives IDE restarts via workspace storage). */
@State(name = "DshProjectState", storages = [Storage("dsh-rider-project.xml")])
@Service(Service.Level.PROJECT)
class DshProjectState : PersistentStateComponent<DshProjectState.State> {

    class State {
        var sessionId: String = ""
        var lastSeq: Long = -1
    }

    private var state = State()

    var sessionId: String
        get() = state.sessionId
        set(value) { state.sessionId = value }

    var lastSeq: Long
        get() = state.lastSeq
        set(value) { state.lastSeq = value }

    override fun getState(): State = state

    override fun loadState(value: State) {
        state = value
    }

    companion object {
        fun getInstance(project: Project): DshProjectState =
            project.getService(DshProjectState::class.java)
    }
}
