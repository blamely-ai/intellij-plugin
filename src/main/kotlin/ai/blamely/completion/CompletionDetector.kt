package ai.blamely.completion

import ai.blamely.git.GitUtils
import ai.blamely.utils.BlamelyLogger
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.awt.datatransfer.DataFlavor
import java.io.File

// Inserts shorter than this and without a newline are treated as plain
// keystrokes.
private const val MIN_COMPLETION_CHARS = 8

// CompletionDetector observes IntelliJ DocumentListener events and posts
// inline-completion-shaped inserts to the blamely daemon as gen_type=
// completion. Scoped per-project: each instance filters to files under the
// owning project's base path, so two open projects don't double-emit for
// the same file.
//
// Same heuristics as the VS Code plugin:
//   - Insertion length ≥ MIN_COMPLETION_CHARS, OR contains '\n'
//   - newFragment non-empty (not a pure deletion)
//   - Clipboard contents don't match the inserted text (filters paste)
//   - File is under the project's base path
//
// Background-thread offload: the listener fires on the EDT inside a write
// action. We push work onto a pooled thread to keep the IDE responsive.
@Service(Service.Level.PROJECT)
class CompletionDetector(private val project: Project) : Disposable {

    private val daemon = DaemonClient()

    @Volatile
    private var lastClipboardText: String = ""

    @Volatile
    private var lastClipboardReadMillis: Long = 0

    fun register() {
        if (project.isDisposed) return
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
        // The listener is unregistered automatically when its parent
        // Disposable (this) is disposed.
    }

    private fun handle(event: DocumentEvent) {
        val newFragment = event.newFragment.toString()
        if (!looksLikeCompletion(newFragment)) return

        val doc = event.document
        val vFile = FileDocumentManager.getInstance().getFile(doc) ?: return
        if (!vFile.isInLocalFileSystem) return
        val absPath = vFile.path
        val basePath = project.basePath ?: return
        if (!absPath.startsWith(basePath)) return // not this project's file

        val startLine = doc.getLineNumber(event.offset) + 1 // 0-based → 1-based
        val newlineCount = newFragment.count { it == '\n' }

        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            refreshClipboardCache()
            if (isLikelyPaste(newFragment)) return@executeOnPooledThread

            val repoRoot = GitUtils.getRepoRoot(absPath) ?: return@executeOnPooledThread
            val relPath = relativePath(repoRoot, absPath) ?: return@executeOnPooledThread

            val tool = resolveTool()
            val rawMeta = """{"source":"intellij_plugin","chars":${newFragment.length}}"""
            val payload = EditPayload(
                tool = tool,
                confidence = "medium",
                genType = "completion",
                repoPath = repoRoot,
                filePath = relPath,
                suggestedLines = (newlineCount + 1).toLong(),
                lines = listOf(EditRange(start = startLine, end = startLine + newlineCount)),
                rawMeta = rawMeta,
            )
            daemon.send(payload)
        }
    }

    private fun looksLikeCompletion(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text.contains('\n')) return true
        return text.length >= MIN_COMPLETION_CHARS
    }

    private fun refreshClipboardCache() {
        val now = System.currentTimeMillis()
        if (now - lastClipboardReadMillis < 1000) return
        lastClipboardReadMillis = now
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
        if (text.length >= 16 && clip.startsWith(text)) return true
        if (clip.length >= 16 && text.startsWith(clip)) return true
        return false
    }
}

private fun relativePath(repoRoot: String, absPath: String): String? {
    return try {
        val rel = File(absPath).relativeToOrNull(File(repoRoot))?.path ?: return null
        if (rel.isEmpty() || rel.startsWith("..")) null
        else rel.replace('\\', '/')
    } catch (_: Exception) {
        null
    }
}

// Map the host IDE / installed inline-completion plugin onto the store's
// fixed Tool taxonomy (claude / cursor / codex / copilot / human /
// copypaste). IntelliJ-family IDEs don't host Cursor; the dominant
// inline-completion provider is Copilot, so default to "copilot".
private fun resolveTool(): String {
    val pluginManager = try {
        com.intellij.ide.plugins.PluginManagerCore.getPlugins()
    } catch (_: Throwable) {
        emptyArray()
    }
    val hasCopilot = pluginManager.any {
        val id = it.pluginId.idString
        it.isEnabled && id.contains("copilot", ignoreCase = true)
    }
    if (hasCopilot) return "copilot"
    // Fallback when neither Copilot nor any other recognised provider is
    // installed. Cursor is the closest existing label for "AI inline
    // completion of unknown provenance"; users can override later if a
    // dedicated tool tag is added.
    return "cursor"
}
