package ai.blamely.completion

import ai.blamely.cli.CliDataService
import ai.blamely.cli.CliRepoId
import ai.blamely.core.BlameMapService
import ai.blamely.core.BlameUpdateListener
import ai.blamely.core.LineBlame
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

// CompletionDetector attributes AI edits using DETERMINISTIC action signals,
// mirroring VS Code's CompletionDetector approach.
//
// INLINE COMPLETIONS — beforeActionPerformed (critical timing):
//   afterActionPerformed fires AFTER the action runs, meaning the document
//   change has already been processed by the DocumentListener before the flag
//   is set. We use beforeActionPerformed instead: it fires BEFORE the action
//   inserts text into the document, so the flag is ready when DocumentEvent
//   arrives. This is the IntelliJ equivalent of VS Code's Tab keybinding that
//   calls signalInlineAccept() before editor.action.inlineSuggest.commit.
//
// CHAT APPLIES — afterActionPerformed:
//   Chat "Apply in Editor" triggers a document change that may be delayed by
//   tens of milliseconds after the action. afterActionPerformed is fine here
//   because we hold the flag open for 1500 ms.
//
// Background-thread offload: the document listener fires on the EDT inside a
// write action. Work is pushed onto a pooled thread to keep the IDE responsive.
@Service(Service.Level.PROJECT)
class CompletionDetector(private val project: Project) : Disposable {

    private val daemon = DaemonClient()

    @Volatile private var lastClipboardText: String = ""
    @Volatile private var lastClipboardReadMillis: Long = 0

    // Pending-signal state: set by the action listener when the relevant action
    // fires; consumed on the next documentChanged. Exactly one drives gen_type.
    @Volatile private var pendingInlineAccept = false
    private var pendingInlineTimer: ScheduledFuture<*>? = null
    @Volatile private var pendingChatApply = false
    private var pendingChatTimer: ScheduledFuture<*>? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "blamely-signal-reset").also { it.isDaemon = true }
    }

    private fun debugEnabled(): Boolean = try {
        ai.blamely.settings.BlamelySettings.getInstance().debugDetection
    } catch (_: Throwable) {
        false
    }

    fun register() {
        if (project.isDisposed) return

        // AnActionListener.TOPIC is an application-level topic published to the
        // APPLICATION message bus. Subscribing via project.messageBus never
        // receives these events because the topic has BroadcastDirection.NONE.
        val busConn = ApplicationManager.getApplication().messageBus.connect(this)
        busConn.subscribe(
            AnActionListener.TOPIC,
            object : AnActionListener {

                // beforeActionPerformed fires BEFORE the action mutates the
                // document, so the pending flag is already set when DocumentEvent
                // arrives — same ordering as VS Code's Tab keybinding signal.
                //
                // BOTH inline completions and chat applies are pre-signalled here:
                // some Copilot chat "Apply in Editor" actions mutate the document
                // synchronously DURING the action, so afterActionPerformed would be
                // too late (the documentChanged listener already ran with no flag).
                //
                // Guard event.project so multiple open projects don't each set
                // their own flag and produce duplicate daemon records.
                override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
                    if (event.project != project) return
                    val id = try {
                        ActionManager.getInstance().getId(action)
                    } catch (_: Throwable) {
                        null
                    } ?: return
                    // Chat-apply checked FIRST: a Copilot chat "Insert at Caret" id
                    // contains both "copilot" and "insert", which the inline matcher
                    // would otherwise claim.
                    if (isChatApplyAction(id)) {
                        pendingChatApply = true
                        pendingChatTimer?.cancel(false)
                        pendingChatTimer = scheduler.schedule({
                            pendingChatApply = false
                        }, 1500, TimeUnit.MILLISECONDS)
                        if (debugEnabled()) BlamelyLogger.info("chat-apply pre-signal: $id")
                    } else if (isInlineCompletionAcceptAction(id)) {
                        pendingInlineAccept = true
                        pendingInlineTimer?.cancel(false)
                        pendingInlineTimer = scheduler.schedule({
                            pendingInlineAccept = false
                        }, 500, TimeUnit.MILLISECONDS)
                        if (debugEnabled()) BlamelyLogger.info("inline-accept pre-signal: $id")
                    }
                }

                // afterActionPerformed is a BACKSTOP for chat applies whose document
                // change streams in after the action completes (within the 1500 ms
                // window). The id may also only resolve cleanly here for some actions.
                override fun afterActionPerformed(
                    action: AnAction,
                    event: AnActionEvent,
                    result: AnActionResult,
                ) {
                    if (event.project != project) return
                    val id = try {
                        ActionManager.getInstance().getId(action)
                    } catch (_: Throwable) {
                        null
                    } ?: return
                    if (debugEnabled()) BlamelyLogger.info("action: $id")
                    if (isChatApplyAction(id)) {
                        pendingChatApply = true
                        pendingChatTimer?.cancel(false)
                        pendingChatTimer = scheduler.schedule({
                            pendingChatApply = false
                        }, 1500, TimeUnit.MILLISECONDS)
                        if (debugEnabled()) BlamelyLogger.info("chat-apply matched: $id")
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

    // Immediately inserts an AI LineBlame entry for the accepted completion lines
    // into the in-memory BlameMap and fires blameUpdated(). This makes the AI
    // gutter icon appear on the frame after the Tab-accept, bypassing the
    // CliDataService 2-second background refresh cycle entirely.
    private fun pushImmediateBlame(
        relPath: String,
        startLine: Int,
        endLine: Int,
        tool: String,
        genType: String,
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val blameService = project.getService(BlameMapService::class.java) ?: return@invokeLater
            val blameMap = blameService.blameMap
            val existing = blameMap.getBlame(relPath).toMutableList()
            val now = java.time.Instant.now().toString()
            for (ln in startLine..endLine) {
                existing.removeAll { it.lineNumber == ln }
                existing.add(
                    LineBlame(
                        lineNumber = ln,
                        authorType = LineBlame.AuthorType.AI,
                        provider = tool,
                        timestamp = now,
                        interactionType = genType,
                        aiChars = 1,
                        humanChars = 0,
                    )
                )
            }
            blameMap.setFileBlame(relPath, existing)
            // Mark this paint so an in-flight CliDataService.refresh() (whose data
            // predates this completion) skips its clear+rebuild and doesn't flip
            // the gutter AI→Human→AI.
            blameService.lastOptimisticPaintMs = System.currentTimeMillis()
            // Bridge the daemon-write race: the post-send refresh runs immediately
            // after daemon.send() returns 204, but the daemon may not have committed
            // this row to SQLite yet. Register the accepted lines so refresh() re-asserts
            // them as AI until the row is persisted (then it clears them).
            blameService.markPendingAiLines(relPath, startLine..endLine, tool, null, genType)
            project.messageBus.syncPublisher(BlameUpdateListener.TOPIC).blameUpdated()
        }
    }

    // Saves the given document (if unsaved) on the EDT, then triggers the
    // authoritative CliDataService refresh. Ordering matters: refresh runs
    // `git diff HEAD` against disk to narrow wide chat applies to the lines
    // that actually changed, so the file must be flushed FIRST. invokeLater
    // runs after the current document-change write action completes, which is
    // when saveDocument is permitted.
    private fun saveDocumentThenRefresh(doc: com.intellij.openapi.editor.Document) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            try {
                val fdm = FileDocumentManager.getInstance()
                if (fdm.isDocumentUnsaved(doc)) fdm.saveDocument(doc)
            } catch (_: Throwable) {
                // save can fail during shutdown / read-only VFS — refresh anyway
            }
            project.getService(CliDataService::class.java)?.refresh()
        }
    }

    override fun dispose() {
        scheduler.shutdownNow()
    }

    private fun handle(event: DocumentEvent) {
        val newFragment = event.newFragment.toString()
        if (newFragment.isEmpty()) return

        // Consume the pending flags before any early return. Chat-apply wins
        // over inline if both somehow fired.
        val chatApply = pendingChatApply
        if (chatApply) {
            pendingChatApply = false
            pendingChatTimer?.cancel(false)
        }
        val inlineAccept = pendingInlineAccept
        if (inlineAccept) {
            pendingInlineAccept = false
            pendingInlineTimer?.cancel(false)
        }

        // STRICT RULE: only a command/action signal makes an edit AI. No signal
        // → the human author is typing/pasting/refactoring → record nothing.
        val genType = when {
            chatApply -> "chat"
            inlineAccept -> "completion"
            else -> return
        }

        val doc = event.document
        val vFile = FileDocumentManager.getInstance().getFile(doc) ?: return
        if (!vFile.isInLocalFileSystem) return
        val absPath = vFile.path
        val repoRoot = GitUtils.getRepoRoot(absPath) ?: return

        // Pause during cherry-pick/merge/revert/rebase: edits applied by replaying
        // history aren't fresh authorship. content_sha re-attributes them after.
        if (GitUtils.inProgressGitOp(repoRoot)) return

        // Only handle files whose git root matches this project. Without this
        // check every open project's CompletionDetector handles the same event,
        // producing duplicate daemon records.
        val projectRoot = GitUtils.getRepoRoot(project)
        
        if (projectRoot != null && repoRoot != projectRoot) return

        if (GitUtils.toRepoRelativePath(repoRoot, absPath) == null) return

        // Narrow to the lines that ACTUALLY changed (strip common leading and
        // trailing lines between oldFragment and newFragment), so an "apply"
        // that rewrites a big region but alters only a few lines is attributed
        // to those lines, not the whole span.
        val band = narrowedBand(doc, event.offset, event.oldFragment.toString(), newFragment)

        // Compute a per-line content_sha for the changed band while still on the
        // EDT (document access is valid here, and the post-change document already
        // holds the inserted AI lines). Sending these makes the recorded AI lines
        // drift-resistant in CliDataService.refresh(): a current line is matched to
        // this edit by content hash even after its line number shifts, so the gutter
        // no longer flips AI→Human when the file is edited or reopened. Mirrors the
        // reader's hash (CliDataService.lineSha: sha256 of the line with a trailing
        // \r stripped) and the Go log-parsers (copilot_chat.go).
        val lineRanges = buildLineRangesWithSha(doc, band.first, band.second)

        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            refreshClipboardCache()
            if (isLikelyPaste(newFragment)) return@executeOnPooledThread

            val relPath = GitUtils.toRepoRelativePath(repoRoot, absPath)
                ?: return@executeOnPooledThread
            val repoId = CliRepoId.get(repoRoot) ?: repoRoot

            val tool = resolveTool()
            val signal = if (chatApply) "chat_apply_action" else "inline_accept_action"
            val rawMeta = """{"source":"intellij_plugin","chars":${newFragment.length},"gen_type_signal":"$signal"}"""
            val payload = EditPayload(
                tool = tool,
                confidence = "high", // an action proved it
                genType = genType,
                repoPath = repoId,
                filePath = relPath,
                suggestedLines = (band.second - band.first + 1).toLong(),
                lines = lineRanges,
                rawMeta = rawMeta,
                branch = GitUtils.getBranchName(repoRoot),
            )
            if (debugEnabled()) {
                BlamelyLogger.info("record: tool=$tool gen_type=$genType $relPath L${band.first}-${band.second}")
            }
            // Optimistic gutter update: push an AI entry into the BlameMap
            // immediately on the EDT, before the background CliDataService
            // refresh cycle runs. This makes the AI icon appear instantly on
            // Tab-accept instead of waiting up to 2 seconds for the next
            // CliDataService.refresh() poll.
            pushImmediateBlame(relPath, band.first, band.second, tool, genType)

            if (daemon.send(payload)) {
                // Save THIS document, then refresh — in that order. The authoritative
                // CliDataService.refresh() runs `git diff HEAD` (disk) to constrain a
                // wide chat apply to the lines that truly changed. If the buffer is
                // unsaved, git diff sees nothing and the AI lines get stripped to
                // Human. Saving the captured `doc` (no fragile path lookup) reproduces
                // VS Code's precondition exactly. Saving doesn't alter content, so it
                // won't re-trigger documentChanged.
                saveDocumentThenRefresh(doc)
            }
        }
    }

    // buildLineRangesWithSha returns one EditRange per line of the inclusive
    // 1-based [startLine, endLine] band, each carrying the SHA-256 of that line's
    // current text (trailing \r stripped). Tight per-line ranges keep
    // boundedAiRange=true (correct immediately) while the content_sha keeps the
    // attribution correct after the line drifts. Falls back to a single
    // sha-less range if no lines are readable. Must be called on the EDT.
    private fun buildLineRangesWithSha(
        doc: com.intellij.openapi.editor.Document,
        startLine: Int,
        endLine: Int,
    ): List<EditRange> {
        val out = ArrayList<EditRange>((endLine - startLine + 1).coerceAtLeast(1))
        for (ln in startLine..endLine) {
            val idx = ln - 1
            if (idx < 0 || idx >= doc.lineCount) continue
            val s = doc.getLineStartOffset(idx)
            val e = doc.getLineEndOffset(idx)
            val text = doc.getText(com.intellij.openapi.util.TextRange(s, e))
            out.add(EditRange(ln, ln, sha256Hex(text.removeSuffix("\r"))))
        }
        return out.ifEmpty { listOf(EditRange(startLine, endLine)) }
    }

    // narrowedBand returns the inclusive 1-based [startLine, endLine] of the
    // lines that actually changed, by stripping whole common leading/trailing
    // lines shared between oldFrag and newFrag. Char-level common prefix/suffix
    // is snapped to line boundaries so a mid-line start maps cleanly through the
    // document's offset→line function. Falls back to the full fragment span.
    private fun narrowedBand(
        doc: com.intellij.openapi.editor.Document,
        offset: Int,
        oldFrag: String,
        newFrag: String,
    ): Pair<Int, Int> {
        val fullStart = doc.getLineNumber(offset.coerceIn(0, doc.textLength)) + 1
        val fullEnd = doc.getLineNumber((offset + newFrag.length).coerceIn(0, doc.textLength)) + 1
        if (newFrag.isEmpty()) return fullStart to fullStart

        // Common char prefix, snapped back to the last newline (whole lines only).
        val maxP = minOf(oldFrag.length, newFrag.length)
        var p = 0
        while (p < maxP && oldFrag[p] == newFrag[p]) p++
        val skipPre = newFrag.lastIndexOf('\n', (p - 1).coerceAtLeast(0)).let {
            if (it in 0 until p) it + 1 else 0
        }
        // Common char suffix (not overlapping the skipped prefix), snapped to a newline.
        val maxQ = minOf(oldFrag.length, newFrag.length) - skipPre
        var q = 0
        while (q < maxQ && oldFrag[oldFrag.length - 1 - q] == newFrag[newFrag.length - 1 - q]) q++
        val suffixStartInNew = newFrag.length - q
        val skipSuf = if (q > 0) {
            val nl = newFrag.indexOf('\n', suffixStartInNew)
            if (nl >= 0) newFrag.length - nl else 0
        } else 0

        val changedStart = offset + skipPre
        val changedEndExclusive = (offset + newFrag.length - skipSuf).coerceAtLeast(changedStart + 1)
        if (changedStart >= changedEndExclusive || changedStart > doc.textLength) return fullStart to fullEnd
        val startLine = doc.getLineNumber(changedStart.coerceIn(0, doc.textLength)) + 1
        val endLine = doc.getLineNumber((changedEndExclusive - 1).coerceIn(0, doc.textLength)) + 1
        return startLine to maxOf(endLine, startLine)
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

// Explicit Copilot for JetBrains action IDs — checked before pattern matching
// so the right gen_type is assigned even when the action name is ambiguous.
// Enable `blamely.debugDetection` to log every fired action ID and discover
// IDs for your specific Copilot / IDE version.
private val COPILOT_CHAT_APPLY_IDS = setOf(
    // GitHub Copilot for JetBrains 1.7.x — VERIFIED from the installed plugin's
    // copilot-core.xml. Applying a code block from Copilot Chat / Edit mode shows
    // a diff block in the editor; clicking "Accept" fires copilot.diffBlock.accept.
    "copilot.diffBlock.accept",
    "copilot.chat.inline",
    // Older / alternate Copilot ids and JetBrains AI Chat (kept as fallbacks).
    "copilot.chat.applyInEditor",
    "copilot.applyInEditor",
    "copilot.insertAtCaret",
    "copilot.applyCodeBlock",
    "com.github.copilot.chat.applyInEditor",
    "AIAssistant.Editor.ApplySuggestion",
    "AIAssistant.Chat.ApplyCode",
)

private val COPILOT_INLINE_ACCEPT_IDS = setOf(
    // GitHub Copilot for JetBrains 1.7.x — VERIFIED. NES = "Next Edit Suggestion";
    // copilot.nes.tab is the Tab-accept of a suggested edit (completion-like).
    "copilot.nes.tab",
    // Older plugin versions (pre-InlineCompletionProvider).
    "copilot.applyInlays",
    "copilot.acceptLine",
    "copilot.acceptWord",
    "com.github.copilot.applyInlays",
    "com.github.copilot.acceptLine",
)

// isChatApplyAction returns true for action IDs that apply/insert code FROM a
// chat panel (GitHub Copilot Chat "Insert at Caret"/"Apply", JetBrains AI chat
// apply). Checked BEFORE the inline matcher because a chat insert id also
// contains "copilot"/"insert".
private fun isChatApplyAction(id: String): Boolean {
    if (COPILOT_CHAT_APPLY_IDS.contains(id)) return true
    val l = id.lowercase()
    // Copilot chat/edit "Accept" on an applied diff block (copilot.diffBlock.accept).
    if (l.contains("diffblock") && (l.contains("accept") || l.contains("apply"))) return true
    if (l.contains("copilot") && l.contains("inline") && l.contains("chat")) return true
    if (!l.contains("chat")) {
        // Copilot apply without "chat" in the ID (e.g. copilot.applyInEditor)
        if (l.contains("copilot") && (l.contains("apply") || l.contains("insert"))) return true
        // JetBrains AI apply without "chat" in the ID
        if (l.contains("aiassistant") && (l.contains("apply") || l.contains("insert"))) return true
        // Explicit verbs that inline completions never use
        return l.contains("applyedit") || l.contains("applypatch") || l.contains("applycodeblock")
    }
    return l.contains("apply") || l.contains("insert") || l.contains("accept")
}

// isInlineCompletionAcceptAction returns true for action IDs that correspond
// to accepting an inline AI completion. The patterns cover:
//   - JetBrains AI / Grazie Pro (InsertInlineCompletionAction, etc.)
//   - GitHub Copilot — newer versions use IntelliJ's InlineCompletionProvider
//     API and fire InsertInlineCompletionAction; older versions use their own
//     action IDs listed in COPILOT_INLINE_ACCEPT_IDS.
// Chat actions are excluded (handled by isChatApplyAction).
private fun isInlineCompletionAcceptAction(id: String): Boolean {
    if (COPILOT_INLINE_ACCEPT_IDS.contains(id)) return true
    if (id.contains("chat", ignoreCase = true)) return false
    // Copilot Next Edit Suggestion (NES) Tab-accept: copilot.nes.tab
    if (id.contains("nes", ignoreCase = true) &&
        (id.contains("tab", ignoreCase = true) || id.contains("accept", ignoreCase = true))
    ) {
        return true
    }
    // JetBrains native inline completion API (used by Copilot 2024.1+ and JB AI)
    if (id.contains("InlineCompletion", ignoreCase = true) &&
        (id.contains("Insert", ignoreCase = true) ||
            id.contains("Accept", ignoreCase = true) ||
            id.contains("Apply", ignoreCase = true))
    ) {
        return true
    }
    // Older Copilot or third-party inline providers: IDs with "copilot" + accept verb
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
// sha256Hex hashes a single line's text exactly as the reader expects
// (CliDataService.lineSha): SHA-256 of the UTF-8 bytes, hex-encoded. Callers
// strip a trailing \r first so the hash matches across line endings.
internal fun sha256Hex(s: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun resolveTool(): String {
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
