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

    // Which AI tool to credit for detected edits: "auto" | "copilot" | "cursor" |
    // "gemini". "auto" infers from the installed inline-completion plugin. Set
    // explicitly when the IDE hosts more than one assistant so chat applies and
    // inline completions are attributed to the right (independent) tool.
    var aiTool: String
        get() = state.aiTool
        set(value) {
            state.aiTool = value
        }

    // Log AI-edit detection (executed action ids, chat-apply/inline matches, and
    // recorded edits) to the blamely log. Used to verify attribution and to
    // discover the chat-apply action id the installed assistant uses.
    var debugDetection: Boolean
        get() = state.debugDetection
        set(value) {
            state.debugDetection = value
        }

    // Attribution v2: determine each line's author from the observed edit
    // (diff-based working log) instead of content-hash guessing. Drives the gutter
    // and the committed note. On by default; set false to use the legacy engine.
    var attributionV2: Boolean
        get() = state.attributionV2
        set(value) {
            state.attributionV2 = value
        }

    // Show the one-time "this folder isn't a git repo — run git init?" prompt.
    // Turn off to never be asked (parity with VS Code's blamely.promptGitInit).
    var promptGitInit: Boolean
        get() = state.promptGitInit
        set(value) {
            state.promptGitInit = value
        }

    // Log the daemon↔plugin traffic (socket/HTTP requests + responses) to the
    // blamely log. For diagnosing connectivity, separate from debugDetection
    // (parity with VS Code's blamely.debugConnection).
    var debugConnection: Boolean
        get() = state.debugConnection
        set(value) {
            state.debugConnection = value
        }

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state.showGutterLineIcons = loaded.showGutterLineIcons
        state.detectInlineCompletion = loaded.detectInlineCompletion
        state.aiTool = loaded.aiTool
        state.debugDetection = loaded.debugDetection
        state.attributionV2 = loaded.attributionV2
        state.promptGitInit = loaded.promptGitInit
        state.debugConnection = loaded.debugConnection
    }

    data class State(
        var showGutterLineIcons: Boolean = true,
        var detectInlineCompletion: Boolean = true,
        var aiTool: String = "auto",
        var debugDetection: Boolean = false,
        var attributionV2: Boolean = true,
        var promptGitInit: Boolean = true,
        var debugConnection: Boolean = false,
    )

    private var state = State()

    companion object {
        fun getInstance(): BlamelySettings =
            com.intellij.openapi.application.ApplicationManager.getApplication().getService(BlamelySettings::class.java)
    }
}
