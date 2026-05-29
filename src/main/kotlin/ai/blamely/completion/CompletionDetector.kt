package ai.blamely.completion

import ai.blamely.cli.CliDataService
import ai.blamely.cli.CliRepoId
import ai.blamely.git.GitUtils
import ai.blamely.utils.BlamelyLogger
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionResult
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.DataFlavor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// Inserts shorter than this and without a newline are treated as plain
// keystrokes on the heuristic (medium-confidence) path only.
private const val MIN_COMPLETION_CHARS = 8

// CompletionDetector observes two complementary signals:
//
// HIGH-CONFIDENCE path (primary):
//   Subscribe to AnActionListener on the project message bus. When
//   afterActionPerformed fires for an action whose ID indicates an inline
//   completion acceptance (JetBrains AI: InsertInlineCompletionAction;
//   GitHub Copilot: com.github.copilot plugin actions), set
//   `pendingHighConfidence = true`. The very next DocumentEvent is
//   attributed as confidence="high" — no heuristics involved.
//
//   Recognised action ID patterns:
//     - contains "InlineCompletion" + ("Insert" | "Accept" | "Apply")
//       → JetBrains AI / Grazie Pro / any JetBrains-native inline provider
//     - contains "copilot" (case-insensitive) + ("accept" | "insert" | "apply")
//       → GitHub Copilot IntelliJ plugin
//
// MEDIUM-CONFIDENCE fallback (preserved):
//   For providers that do not fire an identifiable action (rare), fall back
//   to the existing heuristic: insert length ≥ MIN_COMPLETION_CHARS or
//   contains '\n', not a clipboard paste.
//
// Background-thread offload: the document listener fires on the EDT inside a
// write action. Work is pushed onto a pooled thread to keep the IDE responsive.
@Service(Service.Level.PROJECT)
class CompletionDetector(private val project: Project) : Disposable {

    private val daemon = DaemonClient()

    @Volatile private var lastClipboardText: String = ""
    @Volatile private var lastClipboardReadMillis: Long = 0

    // High-confidence state: set by the action listener when an inline
    // completion accept action fires; consumed on the next documentChanged.
    @Volatile private var pendingHighConfidence = false
    private var pendingHighConfTimer: ScheduledFuture<*>? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "blamely-highconf-reset").also { it.isDaemon = true }
    }

    fun register() {
        if (project.isDisposed) return

        // High-confidence path: intercept the exact IDE actions that fire when
        // an inline completion is accepted. These are zero-heuristic signals.
        val busConn = project.messageBus.connect(this)
        busConn.subscribe(
            AnActionListener.TOPIC,
            object : AnActionListener {
                override fun afterActionPerformed(
                    action: AnAction,
                    event: AnActionEvent,
                    result: AnActionResult,
                ) {
                    val id = try {
                        ActionManager.getInstance().getId(action)
                    } catch (_: Throwable) {
                        null
                    } ?: return
                    if (isInlineCompletionAcceptAction(id)) {
                        pendingHighConfidence = true
                        pendingHighConfTimer?.cancel(false)
                        // Safety reset: if no document change follows within
                        // 300 ms, clear the flag so it doesn't accidentally
                        // upgrade a later unrelated edit.
                        pendingHighConfTimer = scheduler.schedule({
                            pendingHighConfidence = false
                        }, 300, TimeUnit.MILLISECONDS)
                    }
                }
            }
        )

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    handle(event)
                }
            },
            this
        )
        BlamelyLogger.info("CompletionDetector: registered for project ${project.name}")
    }

    override fun dispose() {
        scheduler.shutdownNow()
    }

    private fun handle(event: DocumentEvent) {
        val newFragment = event.newFragment.toString()

        // Consume the high-confidence flag before any early return so a
        // filtered-out event doesn't leave the flag set for the next change.
        val highConf = pendingHighConfidence
        if (highConf) {
            pendingHighConfidence = false
            pendingHighConfTimer?.cancel(false)
        }

        // High-conf: any non-empty insert following an accept action is a
        // completion — the action already proved it.
        // Medium-conf: apply the existing size heuristic.
        if (!highConf && !looksLikeCompletion(newFragment)) return
        if (newFragment.isEmpty()) return

        val doc = event.document
        val vFile = FileDocumentManager.getInstance().getFile(doc) ?: return
        if (!vFile.isInLocalFileSystem) return
        val absPath = vFile.path
        val repoRoot = GitUtils.getRepoRoot(absPath) ?: return
        if (GitUtils.toRepoRelativePath(repoRoot, absPath) == null) return

        val startLine = doc.getLineNumber(event.offset) + 1 // 0-based → 1-based
        val newlineCount = newFragment.count { it == '\n' }
        val highConfSnapshot = highConf

        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            refreshClipboardCache()
            if (isLikelyPaste(newFragment)) return@executeOnPooledThread

            val relPath = GitUtils.toRepoRelativePath(repoRoot, absPath)
                ?: return@executeOnPooledThread
            val repoId = CliRepoId.get(repoRoot) ?: repoRoot

            val confidence = if (highConfSnapshot) "high" else "medium"
            val tool = resolveTool()
            // Distinguish a chat / agent-panel apply from an inline completion
            // accept: the high-confidence path means an inline-completion accept
            // action fired (it's a completion); otherwise a multi-line bulk
            // insert (not a paste, not undo) is overwhelmingly a chat / agent
            // "apply in editor" action → gen_type=chat. A single-line non-inline
            // insert is ambiguous, so we leave it as completion.
            val genType = if (!highConfSnapshot && newlineCount >= 1) "chat" else "completion"
            val rawMeta = """{"source":"intellij_plugin","chars":${newFragment.length},"high_conf":$highConfSnapshot}"""
            val payload = EditPayload(
                tool = tool,
                confidence = confidence,
                genType = genType,
                repoPath = repoId,
                filePath = relPath,
                suggestedLines = (newlineCount + 1).toLong(),
                lines = listOf(EditRange(start = startLine, end = startLine + newlineCount)),
                rawMeta = rawMeta,
            )
            if (daemon.send(payload)) {
                project.getService(CliDataService::class.java)?.refresh()
            }
        }
    }

    private fun looksLikeCompletion(text: String): Boolean {
        if (text.isEmpty()) return false
        // Require substantial non-whitespace content. A bare newline or
        // auto-indent (newline + spaces) is just the user pressing Enter —
        // the old `text.contains('\n')` check attributed every Enter keystroke
        // as an AI completion on the medium-confidence path.
        return text.trim().length >= MIN_COMPLETION_CHARS
    }

    private fun refreshClipboardCache() {
        // Always read fresh — only called when a completion candidate exists,
        // so frequency is naturally low. A TTL cache causes paste-after-copy
        // to be mis-attributed when copy and paste happen within the TTL window.
        lastClipboardReadMillis = System.currentTimeMillis()
        try {
            val t = CopyPasteManager.getInstance().contents
            if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                lastClipboardText = t.getTransferData(DataFlavor.stringFlavor) as? String ?: ""
            }
        } catch (_: Exception) {
            // headless / no clipboard — leave cache untouched
        }
    }

    private fun isLikelyPaste(text: String): Boolean {
        val clip = lastClipboardText
        if (clip.isEmpty()) return false
        if (clip == text) return true
        // Allow trailing-whitespace differences.
        val t = text.trimEnd()
        val c = clip.trimEnd()
        if (t.isNotEmpty() && t == c) return true
        // Pasted text is a prefix of the clipboard.
        if (t.length >= MIN_COMPLETION_CHARS && c.startsWith(t)) return true
        // Clipboard is a prefix of the pasted text.
        if (c.length >= MIN_COMPLETION_CHARS && t.startsWith(c)) return true
        // Pasted text appears anywhere inside the clipboard.
        if (t.length >= MIN_COMPLETION_CHARS && c.contains(t)) return true
        return false
    }
}

// isInlineCompletionAcceptAction returns true for action IDs that correspond
// to accepting an inline AI completion. The patterns cover:
//   - JetBrains AI / Grazie Pro (InsertInlineCompletionAction, etc.)
//   - GitHub Copilot IntelliJ plugin (com.github.copilot.* accept/insert/apply)
private fun isInlineCompletionAcceptAction(id: String): Boolean {
    // JetBrains AI: action IDs contain "InlineCompletion" + accept verb
    if (id.contains("InlineCompletion", ignoreCase = true) &&
        (id.contains("Insert", ignoreCase = true) ||
            id.contains("Accept", ignoreCase = true) ||
            id.contains("Apply", ignoreCase = true))
    ) {
        return true
    }
    // GitHub Copilot IntelliJ plugin: IDs contain "copilot" + accept verb
    val lower = id.lowercase()
    if (lower.contains("copilot") &&
        (lower.contains("accept") || lower.contains("insert") || lower.contains("apply"))
    ) {
        return true
    }
    return false
}

// resolveTool maps the host IDE / installed inline-completion plugin onto the
// store's fixed Tool taxonomy. Copilot and Cursor are independent tools —
// neither depends on the other.
//
// The "aiTool" setting is authoritative: when the IDE hosts more than one
// assistant (or auto-detection guesses wrong), the user pins copilot/cursor in
// Settings → Blamely. "auto" infers from the installed plugins: a GitHub
// Copilot plugin → copilot; otherwise cursor.
private fun resolveTool(): String {
    val configured = try {
        ai.blamely.settings.BlamelySettings.getInstance().aiTool
    } catch (_: Throwable) {
        "auto"
    }
    if (configured == "copilot" || configured == "cursor") return configured

    val pluginManager = try {
        com.intellij.ide.plugins.PluginManagerCore.getPlugins()
    } catch (_: Throwable) {
        emptyArray()
    }
    val hasCopilot = pluginManager.any {
        it.isEnabled && it.pluginId.idString.contains("copilot", ignoreCase = true)
    }
    if (hasCopilot) return "copilot"
    return "cursor"
}
