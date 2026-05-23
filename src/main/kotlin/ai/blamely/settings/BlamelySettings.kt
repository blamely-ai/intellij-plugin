package ai.blamely.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "BlamelySettings", storages = [Storage("blamely.xml")])
class BlamelySettings : PersistentStateComponent<BlamelySettings.State> {

    var showGutterLineIcons: Boolean
        get() = state.showGutterLineIcons
        set(value) {
            state.showGutterLineIcons = value
        }

    var detectInlineCompletion: Boolean
        get() = state.detectInlineCompletion
        set(value) {
            state.detectInlineCompletion = value
        }

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state.showGutterLineIcons = loaded.showGutterLineIcons
        state.detectInlineCompletion = loaded.detectInlineCompletion
    }

    data class State(
        var showGutterLineIcons: Boolean = true,
        var detectInlineCompletion: Boolean = true,
    )

    private var state = State()

    companion object {
        fun getInstance(): BlamelySettings =
            com.intellij.openapi.application.ApplicationManager.getApplication().getService(BlamelySettings::class.java)
    }
}
