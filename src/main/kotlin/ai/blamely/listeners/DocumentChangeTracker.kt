package ai.blamely.listeners

import ai.blamely.core.BlameMapService
import ai.blamely.core.BranchSessionLifecycleService
import ai.blamely.core.LineBlame
import ai.blamely.core.TraceStoreService
import ai.blamely.persistence.BlameSerializer
import ai.blamely.settings.BlamelySettings
import ai.blamely.utils.Platform
import ai.blamely.utils.matchSuggestion
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Listens to document changes and attributes inserted lines to AI or human.
 * Mirrors Blamely VS Code ChangeTracker: reindex on change, debounce (150ms), batch AI upgrade,
 * exclude patterns, mark-next-as-AI window, lookup/heuristic detection.
 * Inline completion uses the suggestion/trace match only — not a blanket time window — so manual
 * edits after accepting a suggestion are not attributed as AI while the window is still open.
 */
class DocumentChangeTracker(
    private val project: Project,
    private val onBlameUpdated: () -> Unit
) : DocumentListener {

    private val log = Logger.getInstance(DocumentChangeTracker::class.java)
    private var markNextAsAiUntil = 0L
    @Volatile private var lastAiActionStartedAt = 0L
    /** When user sends a chat message (before Apply), set here so time_waiting_for_ai = applyTime - this. */
    @Volatile private var chatRequestSentAt = 0L
    @Volatile private var nextChangeIsPaste = false
    @Volatile private var undoRedoActiveUntil = 0L
    @Volatile private var rollbackActiveUntil = 0L
    /** Paths where persisted blame was cleared due to bulk rollback events this session (first bulk hit per file). */
    private val rollbackBulkClearedPaths = Collections.synchronizedSet(mutableSetOf<String>())
    private val rollbackUiRefreshAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)
    @Volatile var lastDetectedPrompt: String? = null
        private set
    @Volatile var lastDetectedModel: String? = null
        private set
    @Volatile var lastDetectedProvider: String? = null
        private set
    @Volatile var lastDetectedInteractionType: String? = null
        private set
    private val eventQueue = ConcurrentLinkedQueue<QueuedChange>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)
    private var lastDocChangeLog = 0L
    /** Skip duplicate events: same file, line, and content within this window (ms) to avoid 42 chars for 4 chars. */
    private var lastProcessedEventKey: String? = null
    private var lastProcessedEventTime = 0L
    private val duplicateEventWindowMs = 400L

    private data class QueuedChange(
        val affected: List<LineBlame>,
        val matchedAi: Boolean,
        val providerId: String?,
        val model: String?
    )

    /**
     * Mark document changes within the given window as AI.
     * Called when an AI action is detected (Copilot completion, Chat inline, Chat panel apply, etc.).
     * The window is extended (not reset) if called again while already active.
     */
    fun markNextChangeAsAi(
        durationMs: Long = 500,
        prompt: String? = null,
        model: String? = null,
        provider: String? = null,
        interactionType: String? = null
    ) {
        val now = System.currentTimeMillis()
        if (markNextAsAiUntil < now) {
            lastAiActionStartedAt = if (chatRequestSentAt > 0) chatRequestSentAt else now
            chatRequestSentAt = 0L
        }
        val newDeadline = now + durationMs
        if (newDeadline > markNextAsAiUntil) {
            markNextAsAiUntil = newDeadline
        }
        if (!prompt.isNullOrBlank()) lastDetectedPrompt = prompt
        if (!model.isNullOrBlank()) lastDetectedModel = model
        if (!provider.isNullOrBlank()) lastDetectedProvider = provider
        if (!interactionType.isNullOrBlank()) lastDetectedInteractionType = interactionType
    }

    /** Call when user sends a message in chat panel so time_waiting_for_ai = (apply time - this). */
    fun recordChatRequestSent() {
        chatRequestSentAt = System.currentTimeMillis()
    }

    /** Called by AnActionListener when a paste action is detected. The next document change will be BULK_INSERT. */
    fun notifyPaste() {
        nextChangeIsPaste = true
    }


    /** Called when undo/redo is about to execute. Document changes during this window are not attributed. */
    fun notifyUndoRedo() {
        undoRedoActiveUntil = System.currentTimeMillis() + 2000
    }

    /**
     * Called when git / Local History rollback is about to execute.
     * Bulk document replacements shortly after this clear persisted blame once per path so the
     * map matches reverted content; small edits (typing) are not suppressed so AI/human detection
     * keeps working after rollback.
     */
    fun notifyRollback() {
        val now = System.currentTimeMillis()
        // Action + CommandListener may both fire; only reset per-path clears when starting a new wave.
        if (now > rollbackActiveUntil) {
            rollbackBulkClearedPaths.clear()
        }
        rollbackActiveUntil = now + 5_000L
        ApplicationManager.getApplication().invokeLater(onBlameUpdated)
        rollbackUiRefreshAlarm.cancelAllRequests()
        rollbackUiRefreshAlarm.addRequest(
            { ApplicationManager.getApplication().invokeLater(onBlameUpdated) },
            400,
            false
        )
        rollbackUiRefreshAlarm.addRequest(
            { ApplicationManager.getApplication().invokeLater(onBlameUpdated) },
            5_500,
            false
        )
    }

    override fun documentChanged(event: DocumentEvent) {
        val blameService = project.getService(BlameMapService::class.java)
        if (blameService != null && System.currentTimeMillis() < blameService.commitSuppressUntil) return

        val doc = event.document
        val newFragment = event.newFragment
        val oldFragment = event.oldFragment
        val insertedLineCount = newFragment.split("\n").size
        val deletedLineCount = if (oldFragment.isEmpty()) 0 else oldFragment.split("\n").size

        ApplicationManager.getApplication().runReadAction {
            val basePath = project.basePath
            val file = resolveFileForDocument(doc)
            if (file == null) {
                if (System.currentTimeMillis() - lastDocChangeLog > 5_000) {
                    lastDocChangeLog = System.currentTimeMillis()
                }
                return@runReadAction
            }
            if (basePath == null) return@runReadAction
            val relativePath = toRelativePath(file, basePath) ?: return@runReadAction
            // EditorFactory multicaster fires for every open project. Only attribute when this
            // project owns the file (content roots + editor in this window), so nested/overlapping
            // projects and edits in another window do not update this project's blame map.
            if (!ProjectFileIndex.getInstance(project).isInContent(file)) return@runReadAction
            if (FileEditorManager.getInstance(project).getAllEditors(file).isEmpty()) return@runReadAction

            project.getService(BlameMapService::class.java)?.ensureBranchLoaded()
            val excludePatterns = BlamelySettings.getInstance().mergedExcludePatterns()
            if (excludePatterns.any { relativePath.contains(it) }) return@runReadAction
            val pathLower = relativePath.lowercase()
            val ext = pathLower.substringAfterLast('.')
            if (ext in EXCLUDE_EXTENSIONS || pathLower.endsWith(".min.js") || pathLower.endsWith(".min.css")) return@runReadAction

            val blameService = project.getService(BlameMapService::class.java) ?: return@runReadAction
            val blameMap = blameService.blameMap
            val normalizedPath = Platform.normalizeBlamePersistenceKey(relativePath, basePath)
            val now = System.currentTimeMillis()

            val changeLineIdx = doc.getLineNumber(event.offset)
            val startLine = changeLineIdx + 1
            // 0-based column at change offset (document already reflects the edit when this listener runs).
            val columnAtChange = (event.offset - doc.getLineStartOffset(changeLineIdx)).coerceAtLeast(0)
            // Only reindex when document line count changes. Same-line edits must not shift/remove entries.
            val insertedDocLines = (insertedLineCount - 1).coerceAtLeast(0)
            val oldStr = oldFragment.toString()
            val deletedDocLines: Int
            val reindexStartLine: Int
            when {
                oldFragment.isEmpty() -> {
                    deletedDocLines = 0
                    // VS Code ChangeTracker parity: pure insertion — shift from line *below* the caret when column > 0
                    // so the current line keeps its gutter/blame and new blank lines open below with their own attribution.
                    reindexStartLine =
                        if (insertedDocLines > 0 && columnAtChange > 0) startLine + 1 else startLine
                }
                !oldStr.contains("\n") -> {
                    deletedDocLines = 0
                    reindexStartLine = startLine
                }
                oldStr.startsWith("\n") -> {
                    // Deletion starts with newline (e.g. "\nsecond"): we remove the line(s) after the \n, not the current line.
                    // So deletedDocLines = one fewer; first removed line is startLine+1 (reindex removes [startLine+1, ...] so line 1 is kept).
                    deletedDocLines = (deletedLineCount - 1).coerceAtLeast(0)
                    reindexStartLine = startLine + 1
                }
                oldStr.endsWith("\n") -> {
                    deletedDocLines = (deletedLineCount - 1).coerceAtLeast(0)
                    reindexStartLine = startLine
                }
                else -> {
                    deletedDocLines = deletedLineCount
                    reindexStartLine = startLine
                }
            }

            // Bulk rollback replacement: clear blame once per path, sync indices for any leftover rows,
            // never attribute (matches revert). Non-bulk edits during the short window use normal logic.
            if (now < rollbackActiveUntil && looksLikeBulkRollbackDocumentChange(doc, event)) {
                val firstBulkForPath = rollbackBulkClearedPaths.add(normalizedPath)
                if (firstBulkForPath) {
                    blameMap.removeFile(normalizedPath)
                    BlameSerializer.removeSnapshot(project, normalizedPath)
                } else {
                    if (newFragment.isEmpty() && oldFragment.isNotEmpty()) {
                        blameMap.recordFirstStartCodingTimeIfNeeded()
                        blameMap.decrementCharsForDeletion(normalizedPath, startLine, oldFragment)
                    }
                    if (insertedDocLines != deletedDocLines) {
                        blameMap.reindex(normalizedPath, reindexStartLine, insertedDocLines, deletedDocLines)
                    }
                }
                ApplicationManager.getApplication().invokeLater(onBlameUpdated)
                return@runReadAction
            }

            // Decrement chars before reindex so entries still exist when we reduce their counts.
            if (newFragment.isEmpty() && oldFragment.isNotEmpty()) {
                blameMap.recordFirstStartCodingTimeIfNeeded()
                blameMap.decrementCharsForDeletion(normalizedPath, startLine, oldFragment)
            }
            if (insertedDocLines != deletedDocLines) {
                blameMap.reindex(normalizedPath, reindexStartLine, insertedDocLines, deletedDocLines)
            }

            // During undo/redo only: reindex but don't attribute (rollback handled above).
            if (now < undoRedoActiveUntil) {
                ApplicationManager.getApplication().invokeLater(onBlameUpdated)
                return@runReadAction
            }

            if (newFragment.isEmpty()) {
                if (deletedDocLines > 0 && isChangeFromAi(oldFragment, deletedDocLines)) {
                    blameMap.recordAiDeletion(normalizedPath, reindexStartLine, deletedDocLines)
                }
                ApplicationManager.getApplication().invokeLater(onBlameUpdated)
                return@runReadAction
            }

            // Formatting-only (e.g. Reformat Code): same content, only whitespace changed — reindex already done above; do not re-attribute.
            if (isFormattingOnly(oldFragment, newFragment)) {
                ApplicationManager.getApplication().invokeLater(onBlameUpdated)
                return@runReadAction
            }

            val endLine = startLine + insertedLineCount - 1
            val insertedStr = newFragment.toString()

            // Skip duplicate events (same insert delivered multiple times) so we don't overcount chars/lines.
            // Never suppress newline-only inserts so each time the user presses Enter the new line is counted.
            val isNewlineOnlyInsert = insertedStr.isNotEmpty() && insertedStr.all { it == '\n' }
            val eventKey = "$normalizedPath:$startLine:${newFragment.length}:${insertedStr.take(200).hashCode()}"
            if (!isNewlineOnlyInsert && eventKey == lastProcessedEventKey && (now - lastProcessedEventTime) < duplicateEventWindowMs) {
                ApplicationManager.getApplication().invokeLater(onBlameUpdated)
                return@runReadAction
            }
            if (!isNewlineOnlyInsert) {
                lastProcessedEventKey = eventKey
                lastProcessedEventTime = now
            }

            // Try suggestion match first (mirrors VS Code ChangeTracker processChange)
            val traceService = project.getService(TraceStoreService::class.java)
            val match = traceService?.traceStore?.let { store ->
                matchSuggestion(
                    store.getPendingSuggestions(),
                    insertedStr,
                    normalizedPath,
                    Pair(startLine - 1, 0)
                )
            }

            // Empty line = newline-only or newlines + whitespace (user pressed Enter, maybe with indent). Always human.
            val insertedLinesForEmptyCheck = newFragment.split("\n")
            val isEmptyLineInsert = isNewlineOnlyInsert || (insertedLinesForEmptyCheck.all { it.isBlank() } && newFragment.contains('\n'))

            val isAi: Boolean
            val providerId: String?
            val model: String?
            val prompt: String?

            val isPaste = nextChangeIsPaste.also { if (it) nextChangeIsPaste = false }
            val blameEmpty = blameMap.getBlame(normalizedPath).isEmpty()
            val isNewOrEmptyFile = blameEmpty && (doc.textLength == newFragment.length)

            // Empty-line inserts (Enter, with or without indent) are always human — do not attribute to AI even if window is active.
            val acceptWindowActive = markNextAsAiUntil > now
            if (isEmptyLineInsert) {
                isAi = false
                providerId = null
                model = null
                prompt = null
            } else if (match != null) {
                traceService.traceStore.markAccepted(match.suggestion.suggestionId, insertedStr)
                isAi = acceptWindowActive
                providerId = match.suggestion.providerId
                model = match.suggestion.modelName
                prompt = match.suggestion.prompt
            } else {
                val fromStack = isChangeFromAi(newFragment, insertedLineCount)
                // Do not treat "active AI window" as AI for inline completion: only matched suggestion
                // inserts or AI call-stack edits count (VS Code parity — avoids post-accept typing as AI).
                val allowBlanketAiWindow =
                    lastDetectedInteractionType != "completion"
                val isAiByWindow =
                    acceptWindowActive && !isNewOrEmptyFile && allowBlanketAiWindow
                isAi = isAiByWindow || fromStack
                providerId = if (isAi) ai.blamely.utils.AiContextExtractor.resolveProviderName(lastDetectedProvider) else null
                model = if (isAi) ai.blamely.utils.AiContextExtractor.sanitizeModelForReport(lastDetectedModel) else null
                prompt = if (isAi) lastDetectedPrompt else null
            }

            val codingType = if (isPaste) LineBlame.CodingType.BULK_INSERT else LineBlame.CodingType.TYPING

            val authorType = if (isAi) LineBlame.AuthorType.AI else LineBlame.AuthorType.HUMAN

            if (isAi && lastAiActionStartedAt > 0) {
                val waitMs = System.currentTimeMillis() - lastAiActionStartedAt
                blameMap.addTimeWaitingForAi(waitMs)
                lastAiActionStartedAt = 0
            }

            // Extend window for chat-style applies (many sequential edits). Completion stays short so
            // the user can type immediately after accept without inheriting AI attribution.
            if (isAi && markNextAsAiUntil > 0) {
                val extendMs = if (lastDetectedInteractionType == "completion") 450L else 3000L
                markNextChangeAsAi(extendMs)
            }

            // For chat panel, fill model/prompt/interactionType from UI if missing (e.g. when attributed via stack on background thread).
            // Always set interactionType for AI: completion when we had a suggestion match, else chat_panel (so report never shows suggestion_match for chat).
            var modelForAttr = model
            var promptForAttr = prompt
            var interactionTypeForAttr = when {
                !isAi -> null
                match != null -> when (val it = lastDetectedInteractionType?.takeIf { s -> s.isNotBlank() }) {
                    null -> "completion"
                    "chat_panel", "chat_inline" -> it
                    else -> "completion"
                }
                else -> lastDetectedInteractionType?.takeIf { it.isNotBlank() }
            }
            // Fill model/prompt from UI only when not holding write lock (UI scraping can trigger modal progress and must not run under write action)
            if (isAi && (modelForAttr.isNullOrBlank() || promptForAttr.isNullOrBlank() || interactionTypeForAttr.isNullOrBlank()) && !ApplicationManager.getApplication().isWriteAccessAllowed()) {
                fun fillFromUi() {
                    if (ApplicationManager.getApplication().isWriteAccessAllowed()) return
                    val ctx = ai.blamely.utils.AiContextExtractor.extractFromProject(project)
                    if (modelForAttr.isNullOrBlank() && !ctx.model.isNullOrBlank()) modelForAttr = ai.blamely.utils.AiContextExtractor.sanitizeModelForReport(ctx.model)
                    if (promptForAttr.isNullOrBlank() && !ctx.prompt.isNullOrBlank()) promptForAttr = ctx.prompt
                    if (interactionTypeForAttr.isNullOrBlank()) interactionTypeForAttr = "chat_panel"
                }
                if (ApplicationManager.getApplication().isDispatchThread) fillFromUi()
                else ApplicationManager.getApplication().invokeAndWait { fillFromUi() }
            }

            // Use same empty-line check; count 1 char per newline for empty lines.
            // insertAttributedLineRange (VS Code): gap-only inserts attribute at startLine+1 when column > 0.
            val blameAttrStart: Int
            val blameLineEnd: Int
            val blameCharsInserted: Int
            val blameCharsPerLine: List<Int>
            if (isEmptyLineInsert) {
                val numNewlines = newFragment.count { it == '\n' }
                val gapStart = if (columnAtChange > 0) startLine + 1 else startLine
                blameAttrStart = gapStart
                blameLineEnd = gapStart + numNewlines - 1
                blameCharsInserted = numNewlines
                blameCharsPerLine = List(numNewlines) { 1 }
            } else {
                blameAttrStart = startLine
                blameCharsPerLine = insertedLinesForEmptyCheck.map { seg -> if (seg.isBlank()) 1 else seg.length }
                blameCharsInserted = blameCharsPerLine.sum()
                blameLineEnd = endLine
            }

            blameMap.recordFirstStartCodingTimeIfNeeded()
            val affected = blameMap.setAttribute(
                filePath = normalizedPath,
                lineStart = blameAttrStart,
                lineEnd = blameLineEnd,
                authorType = authorType,
                provider = providerId,
                model = modelForAttr,
                prompt = promptForAttr,
                interactionType = interactionTypeForAttr,
                charsInserted = blameCharsInserted,
                charsPerLineOverride = blameCharsPerLine,
                codingType = codingType
            )
            project.getService(BranchSessionLifecycleService::class.java)?.ensureOpenSessionOnCodeWork()
            val fileName = normalizedPath.substringAfterLast('/').ifEmpty { normalizedPath }
            val extra = if (isAi) " provider=${providerId ?: "-"} model=${model ?: "-"} prompt=${prompt?.take(80) ?: "-"}" else ""
            log.info("Blamely: [BLAME] created file=$fileName lines=$blameAttrStart-$blameLineEnd author=${authorType.name}$extra")

            if (!isAi && markNextAsAiUntil < System.currentTimeMillis()) {
                clearAiContext()
            }

            eventQueue.add(QueuedChange(affected, isAi, providerId, modelForAttr))
            scheduleDebounce()
            ApplicationManager.getApplication().invokeLater(onBlameUpdated)
        }
    }

    private fun scheduleDebounce() {
        alarm.cancelAllRequests()
        alarm.addRequest(
            { ApplicationManager.getApplication().invokeLater { processEventQueue() } },
            150,
            false
        )
    }

    private fun processEventQueue() {
        val batch = mutableListOf<QueuedChange>()
        while (eventQueue.isNotEmpty()) {
            eventQueue.poll()?.let { batch.add(it) }
        }
        if (batch.isEmpty()) return

        val anyMatchedAi = batch.any { it.matchedAi }
        if (!anyMatchedAi) return

        val (providerId, model) = batch.firstOrNull { it.matchedAi }?.let {
            Pair(it.providerId, it.model)
        } ?: Pair(getActiveAiModel(), getActiveAiModel())

        ApplicationManager.getApplication().runReadAction {
            val blameService = project.getService(BlameMapService::class.java) ?: return@runReadAction
            var reattributedCount = 0
            for (q in batch) {
                if (!q.matchedAi && q.affected.isNotEmpty()) {
                    blameService.blameMap.reattributeToAi(q.affected, providerId, model)
                    reattributedCount += q.affected.size
                }
            }
            if (reattributedCount > 0) {
                log.info("Blamely: [BLAME] reattributed $reattributedCount line(s) to AI (provider=$providerId)")
            }
            ApplicationManager.getApplication().invokeLater(onBlameUpdated)
        }
    }

    /**
     * True when the only difference between old and new fragment is whitespace (e.g. Reformat Code).
     * We reindex line numbers but do not re-attribute, so existing AI/human blame is preserved.
     */
    private fun isFormattingOnly(oldFragment: CharSequence, newFragment: CharSequence): Boolean {
        val oldNorm = oldFragment.toString().replace(Regex("\\s+"), " ").trim()
        val newNorm = newFragment.toString().replace(Regex("\\s+"), " ").trim()
        return oldNorm == newNorm
    }

    /**
     * True when the edit is large enough to be a VCS / Local History rollback chunk rather than normal typing.
     * Avoids clearing unrelated files when the user edits elsewhere during rollback.
     */
    private fun looksLikeBulkRollbackDocumentChange(doc: Document, event: DocumentEvent): Boolean {
        if (event.oldLength >= ROLLBACK_BULK_CHAR_THRESHOLD || event.newLength >= ROLLBACK_BULK_CHAR_THRESHOLD) return true
        if (event.oldFragment.contains('\n') || event.newFragment.contains('\n')) return true
        val docLen = doc.textLength
        if (docLen > 0 && event.oldLength * 2 >= docLen) return true
        return false
    }

    /**
     * Treat as AI when:
     * (1) Within the "mark next as AI" window (set by AnActionListener or CommandListener), OR
     * (2) The current call stack contains an AI provider class (fallback for chat panel apply
     *     that bypasses both actions and named commands).
     */
    private fun isChangeFromAi(@Suppress("UNUSED_PARAMETER") change: CharSequence, @Suppress("UNUSED_PARAMETER") lineCount: Int): Boolean {
        if (markNextAsAiUntil > System.currentTimeMillis()) return true
        val fromStack = isCalledFromAiProvider()
        if (fromStack) {
            if (lastDetectedInteractionType.isNullOrBlank()) lastDetectedInteractionType = "chat_panel"
            // Skip UI scraping when holding write lock (e.g. Copilot applying diff) to avoid "modal progress under write action"
            if (!ApplicationManager.getApplication().isWriteAccessAllowed()) {
                val ctx = ai.blamely.utils.AiContextExtractor.extractFromProject(project)
                if (!ctx.prompt.isNullOrBlank()) lastDetectedPrompt = ctx.prompt
                if (!ctx.model.isNullOrBlank()) lastDetectedModel = ctx.model
                if (!ctx.provider.isNullOrBlank()) lastDetectedProvider = ctx.provider
            }
        }
        return fromStack
    }

    /**
     * Inspects the current thread's call stack for known AI provider packages.
     * If any frame originates from Copilot, Codeium, Tabnine, etc., the change is AI-generated.
     */
    private fun isCalledFromAiProvider(): Boolean {
        val stack = Thread.currentThread().stackTrace
        for (frame in stack) {
            val cls = frame.className.lowercase()
            if (AI_STACK_PACKAGES.any { cls.contains(it) }) {
                val rawPackage = AI_STACK_PACKAGES.firstOrNull { cls.contains(it) } ?: "unknown"
                val cleanProvider = ai.blamely.utils.AiContextExtractor.resolveProviderName(rawPackage)
                if (lastDetectedProvider == null) lastDetectedProvider = cleanProvider
                if (lastDetectedInteractionType.isNullOrBlank()) lastDetectedInteractionType = "chat_panel"
                markNextChangeAsAi(3000)
                return true
            }
        }
        return false
    }

    /**
     * Clears stored prompt/model context. Called after the AI window expires and
     * the context has been consumed by a blame entry.
     */
    private fun clearAiContext() {
        lastDetectedPrompt = null
        lastDetectedModel = null
        lastDetectedProvider = null
        lastDetectedInteractionType = null
    }

    companion object {
        private val EXCLUDE_EXTENSIONS = setOf("log", "lock", "lockb", "tmp", "temp", "cache", "map")

        /** Minimum replaced text length (chars) to treat as rollback bulk (not a single keystroke). */
        private const val ROLLBACK_BULK_CHAR_THRESHOLD = 28

        private val AI_STACK_PACKAGES = listOf(
            "com.github.copilot",
            "com.codeium",
            "com.tabnine",
            "com.supermaven",
            "com.codegpt",
            "software.amazon.codewhisperer",
            "software.aws.toolkits",
            "com.jetbrains.ai",
            "com.cursor"
        )
    }

    private fun getActiveAiModel(): String {
        return try {
            val info = com.intellij.openapi.application.ApplicationNamesInfo.getInstance()
            val name = (info.fullProductName ?: info.productName ?: "").lowercase()
            if (name.contains("cursor")) "cursor-ai" else "intellij_ai"
        } catch (_: Throwable) {
            "intellij_ai"
        }
    }

    /**
     * Resolve VirtualFile for a document. Tries FileDocumentManager first, then selected file
     * (so new file being typed in is found), then open files, then any editor with this document.
     */
    private fun resolveFileForDocument(doc: Document): VirtualFile? {
        val fdm = FileDocumentManager.getInstance()
        fdm.getFile(doc)?.let { return it }
        val fem = FileEditorManager.getInstance(project)
        // Selected file(s) first — user typing in a new file has that file selected
        for (vf in fem.selectedFiles) {
            if (fdm.getDocument(vf) === doc) return vf
        }
        for (openFile in fem.openFiles) {
            if (fdm.getDocument(openFile) === doc) return openFile
        }
        val basePath = project.basePath
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.document !== doc) continue
            val vf = editor.virtualFile ?: continue
            if (basePath == null || toRelativePath(vf, basePath) != null) return vf
        }
        return null
    }

    /** Returns project-relative path, or null if file is not under project (e.g. scratch/Dummy.txt). */
    private fun toRelativePath(file: VirtualFile, basePath: String): String? {
        val path = file.path
        val normalizedBase = basePath.trimEnd('/', '\\')
        return when {
            path == normalizedBase -> ""
            path.startsWith(normalizedBase + "/") -> path.substring(normalizedBase.length + 1)
            path.startsWith(normalizedBase + "\\") -> path.substring(normalizedBase.length + 1).replace('\\', '/')
            else -> null  // file outside project (scratch, Dummy.txt, etc.) — do not track
        }
    }
}
