package ai.blamely.ui

import ai.blamely.core.BlameMapService
import ai.blamely.core.BlameUpdateListener
import ai.blamely.core.LineBlame
import ai.blamely.git.GitUtils
import ai.blamely.utils.BlankLines
import ai.blamely.settings.BlamelySettings
import ai.blamely.utils.Platform
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.Icon

/** Plain tooltip lines → HTML so gutter hovers render reliably (newlines + indexing dumb-mode path). */
private fun gutterTooltipHtml(plain: String): String {
    if (plain.isEmpty()) return ""
    val body = buildString(plain.length + plain.count { it == '\n' } * 5) {
        for (c in plain) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\n' -> append("<br/>")
                else -> append(c)
            }
        }
    }
    return "<html><body style='white-space:normal;font-size:11pt;'>$body</body></html>"
}

/**
 * Document-line gutter icons for AI/Human blame (**VS Code `BlameDecorations.ts` parity**).
 *
 * [BlameLineMarkerProvider] was unreliable: daemon line markers only attach when PSI nodes align with
 * line starts; many languages/layouts never satisfy that predicate even though blame exists.
 * Range highlighters use logical document lines and match the status bar / tool window.
 *
 * **Icon rule** (same threshold as [ai.blamely.core.BlameMap]): compare `aiChars` vs `humanChars` on each line;
 * AI brain icon when AI chars ≥ human chars, otherwise the human (user) icon — so mixed edits flip the gutter when dominance changes.
 *
 * Rows with [LineBlame.commitSha] set still decorate (CLI snapshots record HEAD at trace end; filtering only
 * uncommitted lines would hide them). [LineBlame.ChangeType.DELETE] rows are skipped.
 */
class BlameDecorations(private val project: Project) : Disposable {

    private val highlighters = mutableMapOf<Editor, MutableList<RangeHighlighter>>()
    private val debounceAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val daemonRestartAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        project.messageBus.connect(this).subscribe(
            BlameUpdateListener.TOPIC,
            object : BlameUpdateListener {
                override fun blameUpdated() {
                    applyDebounced()
                }
            }
        )
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    applyDebounced()
                    daemonRestartAlarm.cancelAllRequests()
                    daemonRestartAlarm.addRequest({
                        if (!project.isDisposed) {
                            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
                        }
                    }, 150)
                }
            }
        )
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorReleased(event: EditorFactoryEvent) {
                    clearHighlighters(event.editor)
                }
            },
            this
        )
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (EditorFactory.getInstance().getEditors(event.document).any { it.project == project }) {
                        applyDebounced()
                    }
                }
            },
            this
        )
    }

    private fun applyDebounced() {
        debounceAlarm.cancelAllRequests()
        debounceAlarm.addRequest({ doApply() }, 100)
    }

    fun refresh() {
        ApplicationManager.getApplication().invokeLater { doApply() }
    }

    private fun doApply() {
        if (project.isDisposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        val fem = FileEditorManager.getInstance(project)
        val editors = fem.allEditors.filterIsInstance<TextEditor>().map { it.editor }
        val open = editors.toSet()
        for (ed in highlighters.keys.toList()) {
            if (ed !in open) clearHighlighters(ed)
        }
        for (editor in editors) {
            applyGutterForEditor(editor)
        }
    }

    private fun applyGutterForEditor(editor: Editor) {
        clearHighlighters(editor)
        if (!BlamelySettings.getInstance().showGutterLineIcons) return

        val doc = editor.document
        val file = FileDocumentManager.getInstance().getFile(doc) ?: return
        val repoRoot = GitUtils.getRepoRoot(project) ?: project.basePath ?: return
        val path = GitUtils.toRepoRelativePath(repoRoot, file.path)
            ?: toProjectRelativePath(file, repoRoot)
            ?: return

        val blameService = project.getService(BlameMapService::class.java) ?: return
        val raw = blameService.blameMap.getBlame(path).filter { it.changeType != LineBlame.ChangeType.DELETE }
        if (raw.isEmpty()) {
            if (ai.blamely.utils.BlamelyLogger.isDebugEnabled()) {
                ai.blamely.utils.BlamelyLogger.debug("gutter: file=$path repoRoot=$repoRoot -> NO blame entries (no icons)")
            }
            return
        }

        val byLine = LinkedHashMap<Int, LineBlame>()
        for (e in raw) {
            byLine[e.lineNumber] = LineBlame.betterLineEntry(byLine[e.lineNumber], e)
        }

        val debug = ai.blamely.utils.BlamelyLogger.isDebugEnabled()
        if (debug) {
            ai.blamely.utils.BlamelyLogger.debug(
                "gutter: file=$path repoRoot=$repoRoot rawEntries=${raw.size} lines=${byLine.size}"
            )
        }

        val markup = editor.markupModel
        val created = mutableListOf<RangeHighlighter>()
        highlighters[editor] = created

        for (entry in byLine.values.sortedBy { it.lineNumber }) {
            val lineIdx = entry.lineNumber - 1
            if (lineIdx < 0 || lineIdx >= doc.lineCount) continue
            val start = doc.getLineStartOffset(lineIdx)
            var end = doc.getLineEndOffset(lineIdx)
            if (BlankLines.isBlankLine(doc.getText(TextRange(start, end)))) {
                continue
            }
            if (end <= start) end = (start + 1).coerceAtMost(doc.textLength)

            val displayAs = effectiveAuthorType(entry)
            val icon = when (displayAs) {
                LineBlame.AuthorType.AI -> BlamelyIcons.GutterBrain
                LineBlame.AuthorType.HUMAN -> BlamelyIcons.GutterHuman
            }
            if (debug) {
                val reason = if (LineBlame.isAiInteractionType(entry.interactionType)) {
                    "interactionType='${entry.interactionType}' is an AI gen_type"
                } else {
                    "no AI gen_type; aiChars=${entry.aiChars} vs humanChars=${entry.humanChars}"
                }
                ai.blamely.utils.BlamelyLogger.debug(
                    "gutter: line=${entry.lineNumber} icon=$displayAs" +
                        " (reason: $reason)" +
                        " provider=${entry.provider} model=${entry.model}" +
                        " interactionType=${entry.interactionType}" +
                        " aiChars=${entry.aiChars} humanChars=${entry.humanChars}" +
                        " boundedAiRange=${entry.boundedAiRange} ts=${entry.timestamp}"
                )
            }
            val tooltip = blameGutterTooltipText(entry, displayAs, path)

            val hl = markup.addRangeHighlighter(
                start,
                end,
                HighlighterLayer.SYNTAX + 10,
                null,
                HighlighterTargetArea.LINES_IN_RANGE
            )
            hl.gutterIconRenderer = BlameLineGutterRenderer(icon, tooltip, entry.lineNumber, displayAs)
            created.add(hl)
        }
    }

    private fun clearHighlighters(editor: Editor) {
        highlighters.remove(editor)?.forEach { it.dispose() }
    }

    override fun dispose() {
        debounceAlarm.cancelAllRequests()
        daemonRestartAlarm.cancelAllRequests()
        highlighters.values.forEach { list -> list.forEach { it.dispose() } }
        highlighters.clear()
    }

    private class BlameLineGutterRenderer(
        private val myIcon: Icon,
        private val tooltip: String,
        private val line: Int,
        private val authorType: LineBlame.AuthorType
    ) : GutterIconRenderer(), DumbAware {
        override fun getIcon(): Icon = myIcon
        override fun getTooltipText(): String = gutterTooltipHtml(tooltip)
        override fun getAlignment(): Alignment = Alignment.LEFT
        override fun equals(other: Any?): Boolean {
            if (other !is BlameLineGutterRenderer) return false
            return line == other.line && authorType == other.authorType && tooltip == other.tooltip
        }
        override fun hashCode(): Int = 31 * (31 * line + authorType.hashCode()) + tooltip.hashCode()
    }

    companion object {

        /** Matches BlameMap line dominance: AI gutter iff `aiChars >= humanChars` when there is typed content. */
        fun effectiveAuthorType(entry: LineBlame): LineBlame.AuthorType {
            return entry.effectiveAuthorType()
        }

        fun toProjectRelativePath(file: VirtualFile, basePath: String): String? {
            if (basePath.isBlank()) return null
            val path = file.path
            val normalizedBase = basePath.trimEnd('/', '\\')
            val relative = when {
                path == normalizedBase -> ""
                path.startsWith("$normalizedBase/") -> path.substring(normalizedBase.length + 1)
                path.startsWith("$normalizedBase\\") -> path.substring(normalizedBase.length + 1).replace('\\', '/')
                else -> return null
            }
            return Platform.normalizePath(relative)
        }

        fun blameGutterTooltipText(entry: LineBlame): String =
            blameGutterTooltipText(entry, effectiveAuthorType(entry), null)

        fun blameGutterTooltipText(entry: LineBlame, displayAs: LineBlame.AuthorType): String =
            blameGutterTooltipText(entry, displayAs, null)

        fun blameGutterTooltipText(
            entry: LineBlame,
            displayAs: LineBlame.AuthorType,
            relativePath: String?
        ): String {
            val changed = formatBlameChangedDate(entry.timestamp)
            return when (displayAs) {
                LineBlame.AuthorType.AI -> buildString {
                    appendLine("Author: AI")
                    entry.provider?.takeIf { it.isNotBlank() }?.let {
                        appendLine("Tool: ${toolDisplayName(it)}")
                    }
                    entry.model?.takeIf { it.isNotBlank() }?.let {
                        appendLine("Model: $it")
                    }
                    append("Change Date: $changed")
                }
                LineBlame.AuthorType.HUMAN -> buildString {
                    appendLine("Author: Human")
                    append("Change Date: $changed")
                }
            }
        }

        /** Raw provider id (e.g. `codex`, `copilot`) → display label for gutter hover (e.g. `Codex`, `Copilot`). */
        internal fun toolDisplayName(provider: String): String =
            provider.trim().lowercase().replaceFirstChar { it.uppercase() }

        /** ISO-8601 instant → localized date/time for gutter hover (falls back to raw string). */
        internal fun formatBlameChangedDate(isoTimestamp: String): String {
            val raw = isoTimestamp.trim()
            if (raw.isEmpty()) return "Unknown"
            return try {
                val zdt = Instant.parse(raw).atZone(ZoneId.systemDefault())
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(zdt)
            } catch (_: Exception) {
                raw
            }
        }
    }
}
