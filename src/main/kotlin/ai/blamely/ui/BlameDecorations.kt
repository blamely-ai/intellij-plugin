package ai.blamely.ui

import ai.blamely.core.BlameMapService
import ai.blamely.core.BlameUpdateListener
import ai.blamely.core.LineBlame
import ai.blamely.git.GitUtils
import ai.blamely.utils.BlankLines
import ai.blamely.settings.BlamelySettings
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
import com.intellij.util.Alarm
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.Icon

// Per-tool brand colors, matching the VS Code hover (BlameDecorations.ts) and the
// HTML report. Concrete hex — Swing tooltip HTML has no CSS variables; chosen to
// read on both light and dark IDE themes (Cursor's near-white brand → legible blue).
private val TOOL_BRAND_COLORS = mapOf(
    "claude" to "#d97757",
    "cursor" to "#7aa2f7",
    "codex" to "#10a37f",
    "copilot" to "#a371f7",
    "gemini" to "#4f9cf0",
)
private const val HUMAN_COLOR = "#56a064"

private fun toolBrandColor(provider: String?): String =
    TOOL_BRAND_COLORS[provider?.trim()?.lowercase()] ?: "#589df6"

/** Theme-aware dim color for secondary text (model chip, footer, branding). */
private fun dimHex(): String = try {
    val c = com.intellij.util.ui.UIUtil.getContextHelpForeground()
    String.format("#%02x%02x%02x", c.red, c.green, c.blue)
} catch (_: Throwable) {
    "#808080"
}

/** Inline-HTML escape (no newline handling — used inside the rich hover spans). */
private fun escHtml(s: String): String = buildString(s.length) {
    for (c in s) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        else -> append(c)
    }
}

/** Prompt → escaped, trimmed, truncated, curly-quoted body for the hover. */
private fun quotePromptHtml(prompt: String): String {
    val clean = escHtml(prompt.trim())
    val short = if (clean.length > 220) clean.substring(0, 220) + "…" else clean
    return "&#8220;$short&#8221;"
}

/** Dimmed footer: localized date  &middot;  short commit sha (mirrors VS Code metaFooter). */
private fun metaFooterHtml(entry: LineBlame, changedEsc: String, dim: String): String {
    val parts = mutableListOf<String>()
    if (changedEsc.isNotEmpty()) parts.add(changedEsc) // omitted when the date is unknown
    entry.commitSha?.takeIf { it.isNotBlank() }?.let { parts.add(escHtml(it.take(8))) }
    if (parts.isEmpty()) return ""
    return "<span style='color:$dim;'>" + parts.joinToString("&nbsp;&#183;&nbsp;") + "</span>"
}

/** Subtle product attribution shown at the bottom of every hover. */
private fun brandLineHtml(dim: String): String =
    "<span style='color:$dim;'>Provided by <b>Blamely</b></span>"

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
        // Look up by absolute path: the BlameMap is keyed by absolute path so files
        // from any of the project's repos resolve, not just the project's base repo.
        val path = GitUtils.blameKey(file.path)

        val blameService = project.getService(BlameMapService::class.java) ?: return
        val raw = blameService.blameMap.getBlame(path).filter { it.changeType != LineBlame.ChangeType.DELETE }
        if (raw.isEmpty()) {
            if (ai.blamely.utils.BlamelyLogger.isDebugEnabled()) {
                ai.blamely.utils.BlamelyLogger.debug("gutter: file=$path -> NO blame entries (no icons)")
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
                "gutter: file=$path rawEntries=${raw.size} lines=${byLine.size}"
            )
        }

        val markup = editor.markupModel
        val created = mutableListOf<RangeHighlighter>()
        highlighters[editor] = created

        // Neutral "detecting" lines (an AI-likely edit awaiting attribution) render the
        // amber ring UNLESS already resolved to AI — mirrors VS Code BlameDecorations.
        val detectingLines = blameService.detectingLinesFor(path)
        val candidateLines = (byLine.keys + detectingLines).sorted()

        for (line in candidateLines) {
            val lineIdx = line - 1
            if (lineIdx < 0 || lineIdx >= doc.lineCount) continue
            val start = doc.getLineStartOffset(lineIdx)
            var end = doc.getLineEndOffset(lineIdx)
            if (BlankLines.isBlankLine(doc.getText(TextRange(start, end)))) {
                continue
            }
            if (end <= start) end = (start + 1).coerceAtMost(doc.textLength)

            val entry = byLine[line]
            val resolvedAi = entry != null && effectiveAuthorType(entry) == LineBlame.AuthorType.AI

            val icon: Icon
            val authorType: LineBlame.AuthorType?
            val tooltip: String
            val tooltipHtml: String
            when {
                resolvedAi -> {
                    // Resolved to AI — clear any detecting state and show the AI icon.
                    blameService.clearDetectingLine(path, line)
                    icon = BlamelyIcons.GutterBrain
                    authorType = LineBlame.AuthorType.AI
                    tooltip = blameGutterTooltipText(entry!!, LineBlame.AuthorType.AI, path)
                    tooltipHtml = blameGutterTooltipHtml(entry, LineBlame.AuthorType.AI)
                }
                line in detectingLines -> {
                    icon = BlamelyIcons.GutterDetecting
                    authorType = null
                    tooltip = "Detecting authorship…"
                    tooltipHtml = DETECTING_TOOLTIP_HTML
                }
                entry != null -> {
                    icon = BlamelyIcons.GutterHuman
                    authorType = LineBlame.AuthorType.HUMAN
                    tooltip = blameGutterTooltipText(entry, LineBlame.AuthorType.HUMAN, path)
                    tooltipHtml = blameGutterTooltipHtml(entry, LineBlame.AuthorType.HUMAN)
                }
                else -> continue
            }
            if (debug && entry != null) {
                ai.blamely.utils.BlamelyLogger.debug(
                    "gutter: line=$line icon=${authorType ?: "DETECTING"}" +
                        " provider=${entry.provider} model=${entry.model}" +
                        " interactionType=${entry.interactionType}" +
                        " aiChars=${entry.aiChars} humanChars=${entry.humanChars}"
                )
            }

            val hl = markup.addRangeHighlighter(
                start,
                end,
                HighlighterLayer.SYNTAX + 10,
                null,
                HighlighterTargetArea.LINES_IN_RANGE
            )
            hl.gutterIconRenderer = BlameLineGutterRenderer(icon, tooltip, tooltipHtml, line, authorType)
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
        private val tooltipHtml: String,
        private val line: Int,
        private val authorType: LineBlame.AuthorType?
    ) : GutterIconRenderer(), DumbAware {
        override fun getIcon(): Icon = myIcon
        override fun getTooltipText(): String = tooltipHtml
        override fun getAlignment(): Alignment = Alignment.LEFT
        override fun equals(other: Any?): Boolean {
            if (other !is BlameLineGutterRenderer) return false
            return line == other.line && authorType == other.authorType && tooltip == other.tooltip
        }
        override fun hashCode(): Int = 31 * (31 * line + (authorType?.hashCode() ?: 0)) + tooltip.hashCode()
    }

    companion object {

        /** Hover for the neutral "detecting" gutter icon (parity with VS Code DETECTING_HOVER). */
        private val DETECTING_TOOLTIP_HTML: String =
            "<html><body style='white-space:normal;font-size:11pt;'>" +
                "<span style='color:#e0a23d;'>&#8635;&nbsp;<b>Detecting authorship…</b></span><br/>" +
                "<span style='color:${dimHex()};'>Resolving AI vs human</span></body></html>"

        /** Matches BlameMap line dominance: AI gutter iff `aiChars >= humanChars` when there is typed content. */
        fun effectiveAuthorType(entry: LineBlame): LineBlame.AuthorType {
            return entry.effectiveAuthorType()
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
            // Only show the change date when present; an empty timestamp would
            // otherwise render a noisy "Change Date: Unknown" line.
            val changed = entry.timestamp.trim().takeIf { it.isNotEmpty() }
                ?.let { formatBlameChangedDate(entry.timestamp) }
            val lines = mutableListOf<String>()
            when (displayAs) {
                LineBlame.AuthorType.AI -> {
                    lines.add("Author: AI")
                    entry.provider?.takeIf { it.isNotBlank() }?.let { lines.add("Tool: ${toolDisplayName(it)}") }
                    entry.model?.takeIf { it.isNotBlank() }?.let { lines.add("Model: $it") }
                    if (changed != null) lines.add("Change Date: $changed")
                }
                LineBlame.AuthorType.HUMAN -> {
                    lines.add("Author: Human")
                    if (changed != null) lines.add("Change Date: $changed")
                }
            }
            return lines.joinToString("\n")
        }

        /**
         * Rich gutter hover, ported from the VS Code extension (`BlameDecorations.ts`
         * `blameGutterHoverMessage`): a brand-colored tool title with the model as a
         * dim secondary chip, the prompt as a quote, a dim date · commit footer, and a
         * dim "Provided by Blamely" line. Swing tooltip HTML is 3.2 — inline `color`,
         * `<b>`, `<br>`, and numeric entities only (no CSS variables or codicons).
         */
        fun blameGutterTooltipHtml(entry: LineBlame, displayAs: LineBlame.AuthorType): String {
            val dim = dimHex()
            val changed = entry.timestamp.trim().takeIf { it.isNotEmpty() }
                ?.let { escHtml(formatBlameChangedDate(entry.timestamp)) } ?: ""
            val sb = StringBuilder("<html><body style='white-space:normal;font-size:11pt;'>")
            when (displayAs) {
                LineBlame.AuthorType.AI -> {
                    val color = toolBrandColor(entry.provider)
                    val tool = entry.provider?.takeIf { it.isNotBlank() }
                        ?.let { escHtml(toolDisplayName(it)) } ?: "AI"
                    sb.append("<span style='color:$color;'>&#10022;&nbsp;<b>$tool</b></span>")
                    entry.model?.takeIf { it.isNotBlank() }?.let {
                        sb.append("&nbsp;&nbsp;<span style='color:$dim;'>${escHtml(it)}</span>")
                    }
                    sb.append("<br/>")
                    entry.prompt?.takeIf { it.isNotBlank() }?.let {
                        sb.append("<div style='color:$dim;padding:2px 0;'>${quotePromptHtml(it)}</div>")
                    }
                    sb.append(metaFooterHtml(entry, changed, dim)).append("<br/>")
                    sb.append(brandLineHtml(dim))
                }
                LineBlame.AuthorType.HUMAN -> {
                    sb.append("<span style='color:$HUMAN_COLOR;'>&#9679;&nbsp;<b>Human</b></span><br/>")
                    sb.append(metaFooterHtml(entry, changed, dim)).append("<br/>")
                    sb.append(brandLineHtml(dim))
                }
            }
            return sb.append("</body></html>").toString()
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
