package ai.blamely.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level Blamely settings (persisted across restarts).
 *
 * Mirrors the VS Code extension's `blamely.*` settings:
 *
 * - `showGutterLineIcons` ↔ `blamely.showGutterDecorations`
 * - `autoInstallHook`     ↔ `blamely.autoInstallHook`
 * - `reportOnSave`        ↔ `blamely.reportOnSave`
 * - `suggestionTimeoutMs` ↔ `blamely.suggestionTimeout`
 * - `excludePatterns`     ↔ `blamely.excludePatterns`
 */
@Service(Service.Level.APP)
@State(name = "BlamelySettings", storages = [Storage("blamely.xml")])
class BlamelySettings : PersistentStateComponent<BlamelySettings.State> {

    var showGutterLineIcons: Boolean
        get() = state.showGutterLineIcons
        set(value) { state.showGutterLineIcons = value }

    var autoInstallHook: Boolean
        get() = state.autoInstallHook
        set(value) { state.autoInstallHook = value }

    var reportOnSave: Boolean
        get() = state.reportOnSave
        set(value) { state.reportOnSave = value }

    var suggestionTimeoutMs: Int
        get() = state.suggestionTimeoutMs
        set(value) { state.suggestionTimeoutMs = value }

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state.showGutterLineIcons = state.showGutterLineIcons
        this.state.autoInstallHook = state.autoInstallHook
        this.state.reportOnSave = state.reportOnSave
        this.state.suggestionTimeoutMs = state.suggestionTimeoutMs
    }

    data class State(
        var showGutterLineIcons: Boolean = true,
        var autoInstallHook: Boolean = true,
        var reportOnSave: Boolean = true,
        var suggestionTimeoutMs: Int = 30_000
    )

    private var state = State()

    companion object {
        fun getInstance(): BlamelySettings =
            com.intellij.openapi.application.ApplicationManager.getApplication().getService(BlamelySettings::class.java)
    }
}
