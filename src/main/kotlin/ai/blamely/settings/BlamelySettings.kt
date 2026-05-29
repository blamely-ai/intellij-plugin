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

    // Which AI tool to credit for detected edits: "auto" | "copilot" | "cursor".
    // "auto" infers from the installed inline-completion plugin. Set explicitly
    // when the IDE hosts more than one assistant so chat applies and inline
    // completions are attributed to the right (independent) tool.
    var aiTool: String
        get() = state.aiTool
        set(value) {
            state.aiTool = value
        }

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state.showGutterLineIcons = loaded.showGutterLineIcons
        state.detectInlineCompletion = loaded.detectInlineCompletion
        state.aiTool = loaded.aiTool
    }

    data class State(
        var showGutterLineIcons: Boolean = true,
        var detectInlineCompletion: Boolean = true,
        var aiTool: String = "auto",
    )

    private var state = State()

    companion object {
        fun getInstance(): BlamelySettings =
            com.intellij.openapi.application.ApplicationManager.getApplication().getService(BlamelySettings::class.java)
    }
}
