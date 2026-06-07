package ai.blamely.cli

import ai.blamely.core.BlameMap
import ai.blamely.core.BlameMapService
import ai.blamely.core.BlameUpdateListener
import ai.blamely.core.LineBlame
import ai.blamely.git.GitUtils
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import ai.blamely.completion.DaemonClient
import ai.blamely.completion.FsEventPayload
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.Alarm
import java.io.File
import java.time.Instant

/**
 * Read-only bridge to oobeya-cli runtime data (~/.blamely/db.sqlite).
 */
@Service(Service.Level.PROJECT)
class CliDataService(private val project: Project) : Disposable {
    @Volatile
    var daemonStatus: DaemonStatus = DaemonStatus(running = false)
        private set

    // Separate alarms: VFS save coalescing must NOT cancel startup retry timers.
    private val startupAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)
    private val saveAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)
    private val daemonClient = DaemonClient()

    private fun normalizedGenType(genType: String?): String = genType?.trim()?.lowercase() ?: ""
    private fun isInlineCompletionType(genType: String?): Boolean = normalizedGenType(genType) == "completion"
    private fun isAiInteractionType(genType: String?): Boolean = LineBlame.isAiInteractionType(genType)
    /** Max line span for in-memory line→edit maps (avoid huge SQLite Write ranges). */
    private val maxAiLineIndexSpan = 500

    private fun hasBoundedRange(row: CliEditRow): Boolean =
        row.endLine >= row.startLine && row.endLine - row.startLine <= maxAiLineIndexSpan

    private fun isIndexableLineRange(row: CliEditRow): Boolean {
        val start = maxOf(1, row.startLine)
        val end = row.endLine
        return end >= start && end - start <= maxAiLineIndexSpan
    }

    fun start() {
        // Initial load when the project opens (daemon/SQLite/git index may not be ready).
        refresh()
        for (delayMs in listOf(500L, 1500L, 4000L, 8000L, 15000L)) {
            startupAlarm.addRequest({ if (!project.isDisposed) refresh() }, delayMs.toInt())
        }
        // VFS lifecycle: save → refresh; delete/rename/move/copy → update DB then refresh.
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (project.isDisposed) return
                    val base = project.basePath ?: return
                    var needsRefresh = false
                    for (ev in events) {
                        val filePath = ev.file?.path ?: continue
                        if (!filePath.startsWith(base)) continue
                        when (ev) {
                            is VFileContentChangeEvent, is VFileCreateEvent -> {
                                // Standard save/create: just refresh the gutter.
                                // For VFileCreateEvent also restore any soft-deleted attribution
                                // (handles undo of a deletion).
                                if (ev is VFileCreateEvent) {
                                    sendFsCreate(base, filePath)
                                }
                                needsRefresh = true
                            }
                            is VFileDeleteEvent -> {
                                sendFsDelete(base, filePath)
                                needsRefresh = true
                            }
                            is VFileMoveEvent -> {
                                val oldPath = ev.oldPath
                                val newPath = ev.newPath
                                if (oldPath.startsWith(base) && newPath.startsWith(base)) {
                                    sendFsRename(base, oldPath, newPath)
                                }
                                needsRefresh = true
                            }
                            is VFilePropertyChangeEvent -> {
                                // Rename in place: propertyName == "name" when the user
                                // renames a file via the IDE without moving its directory.
                                if (ev.propertyName == "name") {
                                    val dir = ev.file.parent?.path ?: continue
                                    val oldAbs = "$dir/${ev.oldValue}"
                                    val newAbs = "$dir/${ev.newValue}"
                                    if (oldAbs.startsWith(base) && newAbs.startsWith(base)) {
                                        sendFsRename(base, oldAbs, newAbs)
                                    }
                                    needsRefresh = true
                                }
                            }
                            is VFileCopyEvent -> {
                                val srcAbs = ev.file.path
                                // newChildName holds the copy's filename; newParent is the target dir.
                                val dstAbs = "${ev.newParent.path}/${ev.newChildName}"
                                if (srcAbs.startsWith(base) && dstAbs.startsWith(base)) {
                                    sendFsCopy(base, srcAbs, dstAbs)
                                }
                                needsRefresh = true
                            }
                        }
                    }
                    if (needsRefresh) scheduleRefreshOnSave()
                }
            }
        )
        // When the user opens a file tab, reload SQLite + git diff for that editor.
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    scheduleRefreshOnSave()
                }
            }
        )
    }

    // Coalesce the burst of VFS events a single save produces into one refresh,
    // and let the disk write settle before reading git state.
    private fun scheduleRefreshOnSave() {
        if (project.isDisposed) return
        saveAlarm.cancelAllRequests()
        saveAlarm.addRequest({ if (!project.isDisposed) refresh() }, 300)
    }

    // ── fs-event helpers ──────────────────────────────────────────────────────

    private fun repoRelative(base: String, absPath: String): String =
        absPath.removePrefix(base).trimStart('/', '\\').replace('\\', '/')

    private fun repoId(base: String): String =
        GitUtils.getRepoRoot(base)?.let { CliRepoId.get(it) } ?: base

    private fun sendFsCreate(base: String, absPath: String) {
        val rel = repoRelative(base, absPath)
        if (rel.isBlank()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            daemonClient.sendFsEvent(FsEventPayload(kind = "create", repoPath = repoId(base), path = rel))
        }
    }

    private fun sendFsDelete(base: String, absPath: String) {
        val rel = repoRelative(base, absPath)
        if (rel.isBlank()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            daemonClient.sendFsEvent(FsEventPayload(kind = "delete", repoPath = repoId(base), path = rel))
        }
    }

    private fun sendFsRename(base: String, oldAbs: String, newAbs: String) {
        val oldRel = repoRelative(base, oldAbs)
        val newRel = repoRelative(base, newAbs)
        if (oldRel.isBlank() || newRel.isBlank()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            daemonClient.sendFsEvent(FsEventPayload(kind = "rename", repoPath = repoId(base), oldPath = oldRel, newPath = newRel))
        }
    }

    private fun sendFsCopy(base: String, srcAbs: String, dstAbs: String) {
        val srcRel = repoRelative(base, srcAbs)
        val dstRel = repoRelative(base, dstAbs)
        if (srcRel.isBlank() || dstRel.isBlank()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            daemonClient.sendFsEvent(FsEventPayload(kind = "copy", repoPath = repoId(base), srcPath = srcRel, dstPath = dstRel))
        }
    }

    override fun dispose() {
        startupAlarm.cancelAllRequests()
        saveAlarm.cancelAllRequests()
    }

    fun refresh() {
        if (project.isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            // Time this refresh began loading data. If an optimistic AI paint
            // (pushImmediateBlame) happens after this, our data is stale relative
            // to it and we must NOT clobber the gutter — see apply guard below.
            val refreshStartMs = System.currentTimeMillis()
            try {
                val repoRoot = GitUtils.getRepoRoot(project) ?: project.basePath ?: return@executeOnPooledThread
                checkDaemonHealth()

                // Scope by branch-based work session, not a timestamp window: load
                // edits recorded on the current branch and let the git-diff-HEAD
                // intersection below narrow them to the uncommitted lines. This is
                // robust to cherry-pick/squash (no fragile ts cutoff) and switching
                // branches naturally scopes the gutter. A null branch (detached HEAD)
                // loads only un-sessioned rows.
                val branch = GitUtils.getBranchName(repoRoot)
                val headSha = GitUtils.run(repoRoot, "rev-parse", "HEAD")?.trim().orEmpty()

                val edits = CliSqliteReader.loadEditsForRepo(repoRoot, branch, headSha)
                    ?: run {
                        // null = DB read failed (driver, lock, missing sqlite3). Keep gutter.
                        ai.blamely.utils.BlamelyLogger.debug(
                            "refresh: skipped (edit read unavailable) repoRoot=$repoRoot " +
                                "branch=$branch head=${headSha.take(12)}"
                        )
                        return@executeOnPooledThread
                    }

                if (ai.blamely.utils.BlamelyLogger.isDebugEnabled()) {
                    ai.blamely.utils.BlamelyLogger.debug(
                        "refresh: repoRoot=$repoRoot branch=$branch loadedEdits=${edits.size}"
                    )
                    edits.take(50).forEach { r ->
                        ai.blamely.utils.BlamelyLogger.debug(
                            "refresh: edit id=${r.id} tool=${r.tool} gen_type=${r.genType}" +
                                " ${r.filePath} L${r.startLine}-${r.endLine}" +
                                " sha=${r.contentSha != null} ai=${isAiInteractionType(r.genType)}"
                        )
                    }
                }

                // Flush unsaved documents for files with AI edits BEFORE building the
                // blame map and BEFORE running git diff. Two things depend on the file
                // being saved:
                //
                //   1. editsToBlameMap reads File(...).readLines().size to cap line
                //      ranges — if the file still has the old line count (pre-completion),
                //      cappedEnd < startLine for any line appended at the end, and the
                //      loop body never runs → no AI entry → Human gutter.
                //
                //   2. getWorkingTreeHumanLines runs `git diff HEAD` which only sees
                //      saved files — if the file isn't saved, changed = null for that
                //      file and all AI entries are stripped by the constrain step.
                saveDirtyDocumentsForFiles(repoRoot, pathsNeedingFlush(repoRoot, edits.map { it.filePath }))

                val byFile = editsToBlameMap(repoRoot, edits).toMutableMap()

                // Lines that actually differ from HEAD in the working tree.
                val humanLinesByFile = getWorkingTreeHumanLines(repoRoot)
                val changedSets = humanLinesByFile.mapValues { it.value.toHashSet() }

                // Untracked (new) files aren't in `git diff HEAD`; all lines are new.
                val untrackedSet = HashSet<String>()
                GitUtils.run(repoRoot, "ls-files", "--others", "--exclude-standard")?.lines()?.forEach {
                    val fp = it.trim().replace('\\', '/')
                    if (fp.isNotEmpty()) untrackedSet.add(fp)
                }

                val affectedFiles = changedSets.keys + untrackedSet
                applyContentShaAttribution(repoRoot, edits, affectedFiles, byFile)

                scopeToUncommittedWorkingTree(byFile, changedSets, untrackedSet)

                val blameServiceForPending = project.getService(BlameMapService::class.java)
                val hasUncommittedWork = humanLinesByFile.isNotEmpty() || untrackedSet.isNotEmpty()
                if (!hasUncommittedWork) {
                    blameServiceForPending.clearAllPendingAi()
                }

                // git diff HEAD does not include untracked (new) files. When AI generates
                // a new file via a chat panel and the user adds more lines, those human-typed
                // lines are invisible to the diff. For each untracked file that has AI
                // attribution in byFile, add human entries for all non-AI lines.
                val untrackedOut = GitUtils.run(repoRoot, "ls-files", "--others", "--exclude-standard")
                if (untrackedOut != null) {
                    for (line in untrackedOut.lines()) {
                        val fp = line.trim().replace('\\', '/')
                        if (fp.isEmpty() || !byFile.containsKey(fp)) continue
                        // Untracked files have no git-diff to narrow WIDE AI ranges, so a
                        // wide chat/cli row would otherwise blanket the entire new file as
                        // AI. Trust only TIGHT (bounded) AI ranges here; drop wide AI ranges
                        // that can't be verified line-by-line. Recent accepts are still
                        // re-asserted afterwards via the pending-AI overlay.
                        val existing = byFile[fp]!!.filter { e ->
                            e.effectiveAuthorType() != LineBlame.AuthorType.AI || e.boundedAiRange
                        }
                        val aiLineSet = existing
                            .filter { it.effectiveAuthorType() == LineBlame.AuthorType.AI }
                            .mapTo(HashSet()) { it.lineNumber }
                        try {
                            val fileLines = File(repoRoot, fp).readLines().size
                            val humanEntries = (1..fileLines)
                                .filter { ln -> ln !in aiLineSet }
                                .map { ln ->
                                    LineBlame(
                                        lineNumber = ln,
                                        authorType = LineBlame.AuthorType.HUMAN,
                                        timestamp = Instant.now().toString(),
                                        aiChars = 0,
                                        humanChars = 1,
                                    )
                                }
                            byFile[fp] = existing + humanEntries
                        } catch (_: Exception) {}
                    }
                }

                if (hasUncommittedWork) for (path in blameServiceForPending.pendingAiPaths()) {
                    val pending = blameServiceForPending.pendingAiLinesFor(path)
                    if (pending.isEmpty()) continue
                    // Read current text so each pending line can be confirmed against
                    // its captured content_sha before being painted AI (skips human
                    // lines inserted inside the band).
                    val fileLines = try { File(repoRoot, path).readLines() } catch (_: Exception) { null }
                    val entries = byFile[path]?.toMutableList() ?: mutableListOf()
                    var mutated = false
                    for ((ln, p) in pending) {
                        if (p.contentSha != null && fileLines != null) {
                            val text = fileLines.getOrNull(ln - 1)
                            if (text == null || !pendingMatchesLine(p, text)) continue
                        }
                        val idx = entries.indexOfFirst { it.lineNumber == ln }
                        val existing = if (idx >= 0) entries[idx] else null
                        if (existing != null && existing.effectiveAuthorType() == LineBlame.AuthorType.AI) {
                            // SQLite (or contiguous-run expansion) already confirms AI here.
                            blameServiceForPending.clearPendingAiLine(path, ln)
                            continue
                        }
                        val chars = (existing?.humanChars ?: 1).coerceAtLeast(1)
                        val aiEntry = LineBlame(
                            lineNumber = ln,
                            authorType = LineBlame.AuthorType.AI,
                            provider = p.tool,
                            timestamp = Instant.now().toString(),
                            model = p.model,
                            interactionType = p.genType ?: "completion",
                            aiChars = chars,
                            humanChars = 0,
                            boundedAiRange = true,
                        )
                        if (idx >= 0) entries[idx] = aiEntry else entries.add(aiEntry)
                        mutated = true
                    }
                    if (mutated) byFile[path] = entries.sortedBy { it.lineNumber }
                }

                reconcileChangedLinesAttribution(repoRoot, edits, humanLinesByFile, byFile, blameServiceForPending)

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    val blameService = project.getService(BlameMapService::class.java)
                    // Anti-flicker: if an optimistic AI paint happened while this
                    // refresh was loading (its data is older than the paint), skip
                    // the destructive clear+rebuild. The completion's own post-send
                    // refresh — which DOES include the new row — will apply next.
                    if (
                        blameService.lastOptimisticPaintMs > refreshStartMs &&
                        System.currentTimeMillis() - blameService.lastOptimisticPaintMs < 500
                    ) return@invokeLater
                    blameService.lastOptimisticPaintMs = 0
                    if (ai.blamely.utils.BlamelyLogger.isDebugEnabled()) {
                        byFile.forEach { (path, entries) ->
                            val aiLines = entries.filter { it.effectiveAuthorType() == LineBlame.AuthorType.AI }
                                .map { it.lineNumber }.sorted()
                            val humanLines = entries.filter { it.effectiveAuthorType() == LineBlame.AuthorType.HUMAN }
                                .map { it.lineNumber }.sorted()
                            ai.blamely.utils.BlamelyLogger.debug(
                                "refresh: applied file=$path AI=${aiLines.take(40)} HUMAN=${humanLines.take(40)}"
                            )
                        }
                    }
                    // Atomic swap — no clear()-then-repopulate window where the gutter
                    // is momentarily empty.
                    blameService.blameMap.replaceAll(byFile)
                    project.messageBus.syncPublisher(BlameUpdateListener.TOPIC).blameUpdated()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun scopeToUncommittedWorkingTree(
        byFile: MutableMap<String, List<LineBlame>>,
        changedSets: Map<String, Set<Int>>,
        untrackedFiles: Set<String>,
    ) {
        for ((filePath, entries) in byFile.toList()) {
            if (filePath in untrackedFiles) continue
            val changed = changedSets[filePath]
            if (changed.isNullOrEmpty()) {
                byFile.remove(filePath)
                continue
            }
            // Gutter shows ONLY uncommitted working-tree changes (`git diff HEAD`).
            // Do NOT keep content_sha-attributed lines outside that diff: those are
            // ALREADY-COMMITTED AI lines whose text still matches a session
            // content_sha, and keeping them made committed code from an earlier
            // commit linger in the gutter next to a new uncommitted edit in the
            // same file. A genuinely uncommitted AI line always differs from HEAD,
            // so it is already in `changed` — no exception needed.
            byFile[filePath] = entries.filter {
                it.lineNumber in changed
            }
        }
    }

    private fun getWorkingTreeHumanLines(repoRoot: String): Map<String, List<Int>> {
        val out = GitUtils.run(repoRoot, "diff", "--unified=0", "HEAD") ?: return emptyMap()
        val result = mutableMapOf<String, MutableList<Int>>()
        var currentFile: String? = null
        for (line in out.lines()) {
            when {
                line.startsWith("+++ b/") -> {
                    currentFile = line.removePrefix("+++ b/").replace('\\', '/').trim()
                    result.getOrPut(currentFile) { mutableListOf() }
                }
                line.startsWith("+++ /dev/null") -> currentFile = null
                line.startsWith("@@ ") && currentFile != null -> {
                    val m = Regex("\\+(\\d+)(?:,(\\d+))?").find(line) ?: continue
                    val start = m.groupValues[1].toIntOrNull() ?: continue
                    val count = m.groupValues[2].let { if (it.isNotEmpty()) it.toIntOrNull() ?: 1 else 1 }
                    val lines = result.getOrPut(currentFile) { mutableListOf() }
                    for (i in 0 until count) if (start + i > 0) lines.add(start + i)
                }
            }
        }
        return result
    }

    /**
     * Paths whose buffers should be flushed before `git diff HEAD` on refresh.
     * Includes SQLite edit targets, working-tree diffs, untracked files, and
     * currently open editor tabs (so IDE-open loads in-memory changes).
     */
    private fun pathsNeedingFlush(repoRoot: String, editPaths: Collection<String>): Set<String> {
        val paths = editPaths.mapTo(linkedSetOf()) { it.replace('\\', '/') }
        GitUtils.run(repoRoot, "diff", "--name-only", "HEAD")?.lines()?.forEach { line ->
            val fp = line.trim().replace('\\', '/')
            if (fp.isNotEmpty()) paths.add(fp)
        }
        GitUtils.run(repoRoot, "ls-files", "--others", "--exclude-standard")?.lines()?.forEach { line ->
            val fp = line.trim().replace('\\', '/')
            if (fp.isNotEmpty()) paths.add(fp)
        }
        try {
            ApplicationManager.getApplication().invokeAndWait {
                if (project.isDisposed) return@invokeAndWait
                for (file in FileEditorManager.getInstance(project).openFiles) {
                    GitUtils.toRepoRelativePath(repoRoot, file.path)?.let { paths.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        return paths
    }

    // Saves any IntelliJ-unsaved documents for files in the given set, so that
    // the subsequent `git diff HEAD` reflects in-memory completions/edits.
    // Must be called from a pooled thread; switches to EDT via invokeAndWait.
    private fun saveDirtyDocumentsForFiles(repoRoot: String, filePaths: Set<String>) {
        if (filePaths.isEmpty()) return
        try {
            ApplicationManager.getApplication().invokeAndWait {
                val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                val lfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                for (rel in filePaths) {
                    val abs = java.io.File(repoRoot, rel)
                    val vFile = lfs.findFileByIoFile(abs) ?: continue
                    val doc = fdm.getCachedDocument(vFile) ?: continue
                    if (fdm.isDocumentUnsaved(doc)) fdm.saveDocument(doc)
                }
            }
        } catch (_: Exception) {
            // invokeAndWait can throw if the application is being disposed; skip.
        }
    }

    private fun checkDaemonHealth() {
        daemonStatus = CliHealth.check().daemon
    }

    private fun lineSha(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * A pending (optimistic) AI line may only be asserted when the current line
     * text still hashes to the content_sha captured at accept time. This stops a
     * human line inserted in the MIDDLE of a fresh AI band — which slides into the
     * frozen pending line-range — from inheriting AI. Pending entries without a
     * sha (blank lines) keep the legacy line-number bridge.
     */
    private fun pendingMatchesLine(p: BlameMapService.PendingAiLine, text: String): Boolean {
        val sha = p.contentSha ?: return true
        return sha == lineSha(text.removeSuffix("\r"))
    }

    private fun isAiEditRow(row: CliEditRow): Boolean =
        CliSqliteReader.isAiTool(row.tool) || isAiInteractionType(row.genType)

    private fun isChatOrCliGenType(genType: String?): Boolean {
        val g = normalizedGenType(genType)
        return g == "chat" || g == "cli"
    }

    private fun rowCoversLine(row: CliEditRow, ln: Int): Boolean {
        val start = maxOf(1, row.startLine)
        val end = row.endLine
        return ln in start..end
    }

    private fun isLineAttributionCandidate(row: CliEditRow): Boolean {
        if (!isAiEditRow(row)) return false
        val g = normalizedGenType(row.genType)
        return g == "chat" || g == "cli" || (g == "completion" && hasBoundedRange(row))
    }

    /**
     * Newest-first SQLite rows: match content_sha for this line before falling
     * back to line-number ranges, so later whole-file applies do not overwrite
     * tooltips with the last model for every line.
     */
    private fun resolveAiEditForChangedLine(
        filePath: String,
        ln: Int,
        lineText: String,
        edits: List<CliEditRow>,
    ): CliEditRow? {
        val normFile = filePath.replace('\\', '/')
        val hash = lineSha(lineText.removeSuffix("\r"))
        for (row in edits) {
            if (row.filePath.replace('\\', '/') != normFile) continue
            if (!isLineAttributionCandidate(row)) continue
            val sha = row.contentSha
            if (sha != null && sha == hash) return row
        }
        for (row in edits) {
            if (row.filePath.replace('\\', '/') != normFile) continue
            if (!isLineAttributionCandidate(row)) continue
            if (row.contentSha != null) continue
            if (!rowCoversLine(row, ln)) continue
            if (!isIndexableLineRange(row)) continue
            return row
        }
        return null
    }

    private fun applyContentShaAttribution(
        repoRoot: String,
        edits: List<CliEditRow>,
        filePaths: Collection<String>,
        byFile: MutableMap<String, List<LineBlame>>,
    ) {
        val shaByFile = mutableMapOf<String, MutableMap<String, CliEditRow>>()
        for (row in edits) {
            val sha = row.contentSha ?: continue
            if (!isAiEditRow(row)) continue
            val file = row.filePath.replace('\\', '/')
            val bySha = shaByFile.getOrPut(file) { mutableMapOf() }
            if (!bySha.containsKey(sha)) bySha[sha] = row
        }
        for (filePath in filePaths) {
            val norm = filePath.replace('\\', '/')
            val bySha = shaByFile[norm] ?: continue
            val lines = try {
                File(repoRoot, norm).readLines()
            } catch (_: Exception) {
                continue
            }
            val entries = byFile[norm]?.toMutableList() ?: mutableListOf()
            var mutated = false
            for (ln in lines.indices) {
                val text = lines[ln]
                val row = bySha[lineSha(text.removeSuffix("\r"))] ?: continue
                val entry = buildLineBlame(repoRoot, norm, ln + 1, row, contentShaAttributed = true)
                val idx = entries.indexOfFirst { it.lineNumber == ln + 1 }
                if (idx >= 0) {
                    if (entries[idx].effectiveAuthorType() != entry.effectiveAuthorType() ||
                        entries[idx].model != entry.model
                    ) {
                        entries[idx] = entry
                        mutated = true
                    }
                } else {
                    entries.add(entry)
                    mutated = true
                }
            }
            if (mutated) byFile[norm] = entries.sortedBy { it.lineNumber }
        }
    }

    private fun reconcileChangedLinesAttribution(
        repoRoot: String,
        edits: List<CliEditRow>,
        changedByFile: Map<String, List<Int>>,
        byFile: MutableMap<String, List<LineBlame>>,
        blameService: BlameMapService,
    ) {
        for ((filePath, lineNums) in changedByFile) {
            val lines = try { File(repoRoot, filePath).readLines() } catch (_: Exception) { continue }
            val entries = byFile[filePath]?.toMutableList() ?: mutableListOf()
            var mutated = false
            for (ln in lineNums) {
                val text = lines.getOrNull(ln - 1) ?: continue
                val idx = entries.indexOfFirst { it.lineNumber == ln }
                val existing = if (idx >= 0) entries[idx] else null
                // Confirm the line still holds the captured AI text — a human line
                // inserted inside the band slides into the frozen pending range and
                // must not be painted AI.
                val pending = blameService.pendingAiLinesFor(filePath)[ln]
                    ?.takeIf { pendingMatchesLine(it, text) }

                val aiRow = resolveAiEditForChangedLine(filePath, ln, text, edits)

                val entry = when {
                    aiRow != null -> buildLineBlame(repoRoot, filePath, ln, aiRow)
                    pending != null -> LineBlame(
                        lineNumber = ln,
                        authorType = LineBlame.AuthorType.AI,
                        provider = pending.tool,
                        timestamp = Instant.now().toString(),
                        model = pending.model,
                        interactionType = pending.genType ?: "chat",
                        aiChars = 1,
                        humanChars = 0,
                        changeType = LineBlame.ChangeType.ADD,
                        codingType = LineBlame.CodingType.TYPING,
                        boundedAiRange = true,
                    )
                    existing?.effectiveAuthorType() == LineBlame.AuthorType.AI &&
                        isAiInteractionType(existing.interactionType) &&
                        isChatOrCliGenType(existing.interactionType) -> continue
                    else -> LineBlame(
                        lineNumber = ln,
                        authorType = LineBlame.AuthorType.HUMAN,
                        timestamp = Instant.now().toString(),
                        aiChars = 0,
                        humanChars = 1,
                    )
                }

                if (idx >= 0) {
                    if (entries[idx].effectiveAuthorType() != entry.effectiveAuthorType()) {
                        entries[idx] = entry
                        mutated = true
                    }
                } else {
                    entries.add(entry)
                    mutated = true
                }
            }
            if (mutated) byFile[filePath] = entries.sortedBy { it.lineNumber }
        }
    }

    // CONTENT lines (chat applies) carry a per-line content_sha: a current line is
    // attributed to that edit only if its content still hashes to the same value,
    // so human-typed lines inside an AI region aren't mis-credited (and it
    // survives line shifts). RANGE lines (no sha) are attributed by line number.
    private fun editsToBlameMap(repoRoot: String, edits: List<CliEditRow>): Map<String, List<LineBlame>> {
        val assigned = mutableMapOf<String, MutableMap<Int, CliEditRow>>()
        val fileLineCounts = mutableMapOf<String, Int?>()
        for (row in edits) {
            if (
                row.contentSha != null &&
                isAiInteractionType(row.genType) &&
                !isInlineCompletionType(row.genType)
            ) {
                continue
            }
            val byLine = assigned.getOrPut(row.filePath) { mutableMapOf() }
            val hardMax = 50_000
            // Inline completions: trust narrowedBand's exact line range — do NOT cap by
            // file line count. The file may not be saved yet when refresh() reads it, so
            // readLines().size would return the pre-completion count and cappedEnd would
            // be below startLine, making the loop body unreachable → no AI entry → Human.
            // narrowedBand already yields a tight range (1–3 lines), so no over-attribution risk.
            val cappedEnd = if (isInlineCompletionType(row.genType)) {
                minOf(row.endLine, hardMax)
            } else {
                val fileLines = fileLineCounts.getOrPut(row.filePath) {
                    try { File(repoRoot, row.filePath).readLines().size } catch (_: Exception) { null }
                }
                minOf(row.endLine, fileLines ?: hardMax, hardMax)
            }
            for (ln in row.startLine..cappedEnd) {
                if (!byLine.containsKey(ln)) byLine[ln] = row
            }
        }

        val result = mutableMapOf<String, MutableList<LineBlame>>()
        for ((file, byLine) in assigned) {
            result[file] = byLine.entries.map { (ln, row) -> buildLineBlame(repoRoot, file, ln, row) }.toMutableList()
        }
        return result.mapValues { (_, entries) -> entries.sortedBy { it.lineNumber } }
    }

    private fun buildLineBlame(
        repoRoot: String,
        filePath: String,
        lineNumber: Int,
        row: CliEditRow,
        contentShaAttributed: Boolean = false,
    ): LineBlame {
        val ai = isAiEditRow(row)
        // Per-line char counts are not used for attribution (gen_type drives AI/Human)
        // and reading them required document/disk access on a pooled thread — the
        // source of the read-access crash that aborted refresh mid-rebuild. Use a
        // fixed 1 so AI always wins the gutter dedup tiebreak over Human.
        val chars = 1
        val ts = Instant.ofEpochMilli(row.ts / 1_000_000).toString()
        return LineBlame(
            lineNumber = lineNumber,
            authorType = if (ai) LineBlame.AuthorType.AI else LineBlame.AuthorType.HUMAN,
            provider = if (ai) row.tool else null,
            timestamp = ts,
            commitSha = null,
            model = row.model,
            prompt = null,
            interactionType = row.genType,
            aiChars = if (ai) chars else 0,
            humanChars = if (ai) 0 else chars,
            changeType = LineBlame.ChangeType.ADD,
            newLineNumber = lineNumber,
            oldLineNumber = null,
            codingType = LineBlame.CodingType.TYPING,
            ide = null,
            boundedAiRange = ai && isAiInteractionType(row.genType) && hasBoundedRange(row),
            contentShaAttributed = contentShaAttributed,
        )
    }

}
