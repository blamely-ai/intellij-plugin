package ai.blamely.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level Blamely settings (persisted across restarts).
 *
 * Mirrors the VS Code extension `blamely.*` settings (see `reference-project/vscode-plugin/package.json`).
 *
 * - `showGutterLineIcons` ↔ `blamely.showGutterDecorations`
 * - `autoInstallHook`     ↔ `blamely.autoInstallHook`
 * - `reportOnSave`        ↔ `blamely.reportOnSave`
 * - `suggestionTimeoutMs` ↔ `blamely.suggestionTimeout`
 * - `excludePatterns`     ↔ `blamely.excludePatterns` (substring match on relative path; replaces built-in defaults when set)
 * - `additionalExcludePatterns` ↔ `blamely.additionalExcludePatterns` (merged with excludePatterns; defaults include `.snap`)
 *
 * Settings such as `github.copilot.*`, `chat.useHooks`, or `gitai.*` are VS Code / Copilot / GitHub-specific and are not replicated here.
 */
@Service(Service.Level.APP)
@State(name = "BlamelySettings", storages = [Storage("blamely.xml")])
class BlamelySettings : PersistentStateComponent<BlamelySettings.State> {

    var showGutterLineIcons: Boolean
        get() = state.showGutterLineIcons
        set(value) {
            state.showGutterLineIcons = value
        }

    var autoInstallHook: Boolean
        get() = state.autoInstallHook
        set(value) {
            state.autoInstallHook = value
        }

    var reportOnSave: Boolean
        get() = state.reportOnSave
        set(value) {
            state.reportOnSave = value
        }

    var suggestionTimeoutMs: Int
        get() = state.suggestionTimeoutMs
        set(value) {
            state.suggestionTimeoutMs = value
        }

    /** Base exclude patterns (editable); defaults match `DEFAULT_EXCLUDE_PATTERNS`. */
    fun getExcludePatterns(): List<String> = state.excludePatterns.toList()

    /** Extra patterns merged with [getExcludePatterns]; default includes `.snap`. */
    fun getAdditionalExcludePatterns(): List<String> = state.additionalExcludePatterns.toList()

    fun setExcludePatterns(patterns: Collection<String>) {
        state.excludePatterns.clear()
        state.excludePatterns.addAll(patterns.map { it.trim() }.filter { it.isNotEmpty() })
    }

    fun setAdditionalExcludePatterns(patterns: Collection<String>) {
        state.additionalExcludePatterns.clear()
        state.additionalExcludePatterns.addAll(patterns.map { it.trim() }.filter { it.isNotEmpty() })
    }

    /** Distinct merge used by change tracking (same semantics as VS Code `ChangeTracker.handleChange`). */
    fun mergedExcludePatterns(): List<String> =
        (state.excludePatterns.asSequence() + state.additionalExcludePatterns.asSequence())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state.schemaVersion = loaded.schemaVersion
        state.showGutterLineIcons = loaded.showGutterLineIcons
        state.autoInstallHook = loaded.autoInstallHook
        state.reportOnSave = loaded.reportOnSave
        state.suggestionTimeoutMs = loaded.suggestionTimeoutMs
        state.excludePatterns.clear()
        state.excludePatterns.addAll(loaded.excludePatterns)
        state.additionalExcludePatterns.clear()
        state.additionalExcludePatterns.addAll(loaded.additionalExcludePatterns)

        if (loaded.schemaVersion < CURRENT_SCHEMA_VERSION) {
            state.excludePatterns.clear()
            state.excludePatterns.addAll(DEFAULT_EXCLUDE_PATTERNS)
            state.additionalExcludePatterns.clear()
            state.additionalExcludePatterns.add(DEFAULT_ADDITIONAL_EXCLUDE_SNAPSHOT)
            state.schemaVersion = CURRENT_SCHEMA_VERSION
        }
    }

    data class State(
        var schemaVersion: Int = CURRENT_SCHEMA_VERSION,
        var showGutterLineIcons: Boolean = true,
        var autoInstallHook: Boolean = true,
        var reportOnSave: Boolean = true,
        var suggestionTimeoutMs: Int = 30_000,
        var excludePatterns: MutableList<String> = ArrayList(DEFAULT_EXCLUDE_PATTERNS),
        var additionalExcludePatterns: MutableList<String> = arrayListOf(DEFAULT_ADDITIONAL_EXCLUDE_SNAPSHOT),
    )

    private var state = State()

    companion object {
        private const val CURRENT_SCHEMA_VERSION = 2

        /** Default `.snap` entry for `blamely.additionalExcludePatterns` (reference user settings). */
        const val DEFAULT_ADDITIONAL_EXCLUDE_SNAPSHOT = ".snap"

        /**
         * Same entries as the reference VS Code `ChangeTracker.DEFAULT_EXCLUDE_PATTERNS`
         * plus `detector.ai` (short name used in some repos) for parity with the IntelliJ tracker.
         */
        val DEFAULT_EXCLUDE_PATTERNS: List<String> = listOf(
            "node_modules",
            ".git",
            "dist",
            "build",
            "out",
            "target",
            "blamely-detector.ai",
            "detector.ai",
            "blamely-report.md",
            ".log",
            "/log/",
            "\\log\\",
            "/logs/",
            "\\logs\\",
            ".tmp",
            ".temp",
            ".cache",
            ".min.js",
            ".min.css",
            ".lock",
            ".lockb",
            ".idea/",
            ".vscode/",
        )

        fun getInstance(): BlamelySettings =
            com.intellij.openapi.application.ApplicationManager.getApplication().getService(BlamelySettings::class.java)
    }
}
