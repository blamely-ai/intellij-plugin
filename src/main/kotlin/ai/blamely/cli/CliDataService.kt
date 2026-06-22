package ai.blamely.cli

import ai.blamely.core.BlameMap
import ai.blamely.core.BlameMapService
import ai.blamely.core.BlameUpdateListener
import ai.blamely.core.LineBlame
import ai.blamely.git.GitUtils
import ai.blamely.utils.BlankLines
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
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
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
    private val v2Gson = com.google.gson.Gson()
    private val startupAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)
    private val saveAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)
    private val periodicAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)

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
        // Refresh on file saves (manual or autosave) via VFS content-change.
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (project.isDisposed) return
                    val base = project.basePath ?: return
                    val relevant = events.any { ev ->
                        (ev is VFileContentChangeEvent || ev is VFileCreateEvent) &&
                            (ev.file?.path?.startsWith(base) == true)
                    }
                    if (relevant) scheduleRefreshOnSave()
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
        schedulePeriodic()
    }

    // Safety-net poll: new files (and edits whose daemon write lands after the
    // scheduleRefreshOnSave retry ladder) surface within 5s even when no VFS
    // event fires — without this the UI waits for the next user action. Alarm is
    // one-shot, so it re-schedules itself.
    private fun schedulePeriodic() {
        if (project.isDisposed) return
        periodicAlarm.addRequest(
            {
                if (!project.isDisposed) {
                    refresh()
                    schedulePeriodic()
                }
            },
            5000,
        )
    }

    // Coalesce the burst of VFS events a single save produces into one refresh.
    // The 1500ms retry catches the case where the Blamely daemon writes the edit
    // row to SQLite after the first 300ms refresh fires (async DB write race).
    private fun scheduleRefreshOnSave() {
        if (project.isDisposed) return
        saveAlarm.cancelAllRequests()
        saveAlarm.addRequest({ if (!project.isDisposed) refresh() }, 300)
        saveAlarm.addRequest({ if (!project.isDisposed) refresh() }, 1500)
    }

    override fun dispose() {
        startupAlarm.cancelAllRequests()
        saveAlarm.cancelAllRequests()
        periodicAlarm.cancelAllRequests()
    }

    private data class RepoBlame(
        val byFile: Map<String, List<LineBlame>>,
        val hasUncommittedWork: Boolean,
    )

    /**
     * Every distinct git repo this project spans. A project can mix several
     * independent repos (e.g. a parent folder holding backend/ and frontend/, each
     * with its own .git, opened as separate content roots) — or the project dir can
     * itself be the one repo. Without enumerating all of them, only the project's
     * base repo got a gutter; files in the other repos showed no count and no icons.
     * The project base dir is often NOT a git repo in the multi-repo case, so a
     * `.git` presence check filters out non-repo roots (the basePath fallback).
     */
    private fun projectRepoRoots(): List<String> {
        val roots = LinkedHashSet<String>()
        fun consider(path: String?) {
            val p = path ?: return
            if (File(p, ".git").exists()) roots.add(File(p).path)
        }
        consider(GitUtils.getRepoRoot(project))
        try {
            ApplicationManager.getApplication().runReadAction {
                if (project.isDisposed) return@runReadAction
                for (vf in com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots) {
                    consider(GitUtils.getRepoRoot(vf.path))
                }
            }
        } catch (_: Exception) {
        }
        return roots.toList()
    }

    // Attribution v2 repo-wide refresh: rebuild the whole BlameMap from every tracked
    // file's working log (`blamely authorship --all`) so the gutter, status bar, and
    // sidebar all derive from the same v2 source. Runs off-EDT; applies on the EDT.
    private fun refreshV2() {
        if (project.isDisposed) return
        val bin = ai.blamely.authorship.blamelyBinaryPath()
        val merged = HashMap<String, List<LineBlame>>()
        if (java.io.File(bin).exists()) {
            for (repoRoot in projectRepoRoots()) {
                for (wl in fetchAllWorkingLogs(bin, repoRoot)) {
                    val file = wl.file ?: continue
                    merged[GitUtils.blameKey(java.io.File(repoRoot, file).path)] =
                        ai.blamely.authorship.workingLogToLineBlame(wl)
                }
            }
            // Open editors: seed COMMITTED + uncommitted authorship (single-file
            // `authorship` seeds from the commit notes when there's no working log),
            // overriding --all — so a just-committed file keeps its committed history
            // in the gutter instead of showing only the current change.
            for (path in openEditorPaths()) {
                val wl = runAuthorshipSingle(bin, path) ?: continue
                merged[GitUtils.blameKey(path)] = ai.blamely.authorship.workingLogToLineBlame(wl)
            }
        }
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            project.getService(BlameMapService::class.java).blameMap.replaceAll(merged)
            project.messageBus.syncPublisher(BlameUpdateListener.TOPIC).blameUpdated()
        }
    }

    private fun openEditorPaths(): List<String> {
        val out = ArrayList<String>()
        ApplicationManager.getApplication().invokeAndWait {
            if (project.isDisposed) return@invokeAndWait
            for (vf in com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles) {
                if (vf.isInLocalFileSystem) out.add(vf.path)
            }
        }
        return out
    }

    private fun runAuthorshipSingle(bin: String, absPath: String): ai.blamely.authorship.WorkingLogJson? {
        return try {
            val pb = ProcessBuilder(bin, "authorship", absPath)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (proc.waitFor() != 0 || out.isEmpty()) return null
            v2Gson.fromJson(out, ai.blamely.authorship.WorkingLogJson::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchAllWorkingLogs(bin: String, repoRoot: String): List<ai.blamely.authorship.WorkingLogJson> {
        return try {
            val pb = ProcessBuilder(bin, "authorship", repoRoot, "--all")
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (proc.waitFor() != 0 || out.isEmpty()) return emptyList()
            v2Gson.fromJson(out, AllWorkingLogs::class.java)?.files ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class AllWorkingLogs(val files: List<ai.blamely.authorship.WorkingLogJson>? = null)

    fun refresh() {
        if (project.isDisposed) return
        // Attribution v2 owns the gutter/status bar/sidebar — rebuild the map
        // repo-wide from the working logs (one v2 source, I4) instead of the v1
        // SQLite scan. Fixes the previous-commit-then-vanish gutter race (no v1
        // clobber) and keeps the workspace aggregate complete.
        if (ai.blamely.settings.BlamelySettings.getInstance().attributionV2) {
            ApplicationManager.getApplication().executeOnPooledThread { refreshV2() }
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            // Time this refresh began loading data. If an optimistic AI paint
            // (pushImmediateBlame) happens after this, our data is stale relative
            // to it and we must NOT clobber the gutter — see apply guard below.
            val refreshStartMs = System.currentTimeMillis()
            try {
                checkDaemonHealth()
                val blameService = project.getService(BlameMapService::class.java)
                val repoRoots = projectRepoRoots()
                if (repoRoots.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        blameService.blameMap.clear()
                        project.messageBus.syncPublisher(BlameUpdateListener.TOPIC).blameUpdated()
                    }
                    return@executeOnPooledThread
                }

                // Build the blame map once per repo (in repo-relative space, as the
                // SQLite/git helpers expect), then merge under absolute-path keys so
                // identical relative paths in different repos don't overwrite each other.
                val merged = HashMap<String, List<LineBlame>>()
                var anyUncommittedWork = false
                var anyReadFailure = false
                for (repoRoot in repoRoots) {
                    val result = refreshRepo(repoRoot, blameService)
                    if (result == null) {
                        // null = DB read failed (lock/driver). Keep the current gutter
                        // rather than rebuild it incomplete; defer the whole refresh.
                        anyReadFailure = true
                        continue
                    }
                    anyUncommittedWork = anyUncommittedWork || result.hasUncommittedWork
                    for ((rel, entries) in result.byFile) {
                        merged[GitUtils.blameKey(File(repoRoot, rel).path)] = entries
                    }
                }
                if (anyReadFailure) return@executeOnPooledThread

                // Pending-AI overlay is global (keyed by absolute path). Only clear it
                // once NO repo has uncommitted work, so a clean repo can't wipe a dirty
                // repo's pending lines. Repos with work applied their pending in refreshRepo.
                if (!anyUncommittedWork) {
                    blameService.clearAllPendingAi()
                }

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
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
                        merged.forEach { (path, entries) ->
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
                    blameService.blameMap.replaceAll(merged)
                    project.messageBus.syncPublisher(BlameUpdateListener.TOPIC).blameUpdated()
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Build the uncommitted-work blame map (repo-relative keys) for one git repo.
     * Returns null only on a transient SQLite read failure, signalling the caller to
     * keep the current gutter rather than rebuild it incomplete.
     */
    private fun refreshRepo(repoRoot: String, blameService: BlameMapService): RepoBlame? {
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
                ai.blamely.utils.BlamelyLogger.debug(
                    "refresh: skipped (edit read unavailable) repoRoot=$repoRoot " +
                        "branch=$branch head=${headSha.take(12)}"
                )
                return null
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
        applyContentShaAttribution(repoRoot, edits, affectedFiles, byFile, untrackedSet)

        scopeToUncommittedWorkingTree(byFile, changedSets, untrackedSet)

        val hasUncommittedWork = humanLinesByFile.isNotEmpty() || untrackedSet.isNotEmpty()

        // git diff HEAD does not include untracked (new) files. When AI generates
        // a new file via a chat panel and the user adds more lines, those human-typed
        // lines are invisible to the diff. For each untracked file that has AI
        // attribution in byFile, add human entries for all non-AI lines.
        // A brand-new file with no SQLite edits has no byFile entry yet, but it's
        // still all-human work that must show in the gutter (matching the behavior
        // once `git add` makes it appear in `git diff HEAD`), so default to empty.
        for (fp in untrackedSet) {
            // Untracked files have no git-diff to narrow WIDE AI ranges, so a
            // wide chat/cli row would otherwise blanket the entire new file as
            // AI. Trust only TIGHT (bounded) AI ranges here; drop wide AI ranges
            // that can't be verified line-by-line. Recent accepts are still
            // re-asserted afterwards via the pending-AI overlay.
            val existing = (byFile[fp] ?: emptyList()).filter { e ->
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

        // Pending-AI paths are absolute (global across repos); translate each to this
        // repo's relative key and skip those that belong to another repo.
        if (hasUncommittedWork) for (absPath in blameService.pendingAiPaths()) {
            val rel = GitUtils.toRepoRelativePath(repoRoot, absPath) ?: continue
            val pending = blameService.pendingAiLinesFor(absPath)
            if (pending.isEmpty()) continue
            val entries = byFile[rel]?.toMutableList() ?: mutableListOf()
            var mutated = false
            for ((ln, p) in pending) {
                val idx = entries.indexOfFirst { it.lineNumber == ln }
                val existing = if (idx >= 0) entries[idx] else null
                if (existing != null && existing.effectiveAuthorType() == LineBlame.AuthorType.AI) {
                    // SQLite (or contiguous-run expansion) already confirms AI here.
                    blameService.clearPendingAiLine(absPath, ln)
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
            if (mutated) byFile[rel] = entries.sortedBy { it.lineNumber }
        }

        reconcileChangedLinesAttribution(repoRoot, edits, humanLinesByFile, byFile, blameService)

        return RepoBlame(byFile, hasUncommittedWork)
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
            // Keep lines present in the working-tree diff. contentShaAttributed lines
            // whose position matches the changed set are already included; we do NOT
            // add a separate || contentShaAttributed clause because that would let
            // committed AI lines that are still verbatim in the file bleed through
            // (they're identical to HEAD so they're absent from the diff, yet their
            // content hash would still match via applyContentShaAttribution).
            // Drifted uncommitted lines are safe without the clause: a line added
            // since HEAD always appears in `git diff HEAD`, so its new position IS
            // in the changed set regardless of whether it moved.
            byFile[filePath] = entries.filter { it.lineNumber in changed }
        }
    }

    /**
     * Content-aware: a `+` line byte-identical to its positionally-paired `-`
     * line is NOT counted as changed. git emits that pair when a line gains or
     * loses its trailing newline — the "\ No newline at end of file" transition
     * when you append a line after a file whose last line had no newline. Without
     * this, that unchanged last line got a Human gutter icon the moment you
     * pressed Enter. With --unified=0 git emits all `-` then all `+` lines per
     * hunk, so they pair positionally: dels[i] ↔ adds[i].
     */
    private fun getWorkingTreeHumanLines(repoRoot: String): Map<String, List<Int>> {
        val out = GitUtils.run(repoRoot, "diff", "--unified=0", "HEAD") ?: return emptyMap()
        val result = mutableMapOf<String, MutableList<Int>>()
        var currentFile: String? = null
        val dels = mutableListOf<String>()
        val adds = mutableListOf<Pair<Int, String>>()
        var addLine = 0

        fun stripCR(s: String) = s.removeSuffix("\r")

        fun flushHunk() {
            val file = currentFile
            if (file != null) {
                val lines = result.getOrPut(file) { mutableListOf() }
                val n = minOf(dels.size, adds.size)
                for (i in adds.indices) {
                    if (i < n && stripCR(adds[i].second) == stripCR(dels[i])) continue
                    if (adds[i].first > 0) lines.add(adds[i].first)
                }
            }
            dels.clear()
            adds.clear()
        }

        for (line in out.lines()) {
            when {
                line.startsWith("+++ b/") -> {
                    flushHunk()
                    val f = line.removePrefix("+++ b/").replace('\\', '/').trim()
                    currentFile = f
                    result.getOrPut(f) { mutableListOf() }
                }
                line.startsWith("+++ /dev/null") -> { flushHunk(); currentFile = null }
                line.startsWith("@@ ") && currentFile != null -> {
                    flushHunk()
                    val m = Regex("\\+(\\d+)(?:,(\\d+))?").find(line)
                    addLine = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
                currentFile != null -> when {
                    line.startsWith("\\") -> {}       // "\ No newline at end of file"
                    line.startsWith("---") -> {}      // file header, not a delete
                    line.startsWith("-") -> dels.add(line.substring(1))
                    line.startsWith("+++") -> {}      // (handled above; defensive)
                    line.startsWith("+") -> { adds.add(addLine to line.substring(1)); addLine++ }
                }
            }
        }
        flushHunk()
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

    /** Mirrors the CLI's tools.NormalizeLineText: trim + collapse internal whitespace. */
    private fun normalizeLineText(s: String): String =
        s.trim().split(Regex("\\s+")).joinToString(" ")

    /**
     * sha256 of the whitespace-normalized line text, or "" for blank/whitespace-only
     * lines — mirrors content_sha_norm's record-time convention so blank lines never
     * spuriously match each other. Fallback for content_sha when an autoformatter
     * reflows an AI-written line (reindent, trailing whitespace) after the AI wrote it.
     */
    private fun lineShaNorm(s: String): String {
        val norm = normalizeLineText(s)
        if (norm.isEmpty()) return ""
        return lineSha(norm)
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

    // Per-edit occurrence budget. When the SAME content was recorded by several
    // edits (e.g. a chat that wrote 5 identical lines and a later completion that
    // wrote 1), each committed copy must be distributed across those edits by
    // recorded count — otherwise the nearest/newest edit claims them all and the
    // gutter mislabels them (chat lines shown as completion). Mirrors the daemon's
    // pickDriftEdit so the gutter agrees with the commit report. Keyed
    // "$editId:s:$sha" / "$editId:n:$norm".
    private class Budget(
        val recorded: MutableMap<String, Int>,
        val consumed: MutableMap<String, Int> = mutableMapOf(),
    )

    private fun shaKey(id: Long, sha: String) = "$id:s:$sha"
    private fun normKey(id: Long, norm: String) = "$id:n:$norm"
    private fun budgetLeft(b: Budget, key: String) = (b.consumed[key] ?: 0) < (b.recorded[key] ?: 0)
    private fun consumeBudget(b: Budget, key: String) { b.consumed[key] = (b.consumed[key] ?: 0) + 1 }

    /** Build the per-edit recorded-occurrence budget for one file's attribution candidates. */
    private fun buildBudget(normFile: String, edits: List<CliEditRow>): Budget {
        val recorded = mutableMapOf<String, Int>()
        for (row in edits) {
            if (row.filePath.replace('\\', '/') != normFile) continue
            if (!isLineAttributionCandidate(row)) continue
            row.contentSha?.let { recorded[shaKey(row.id, it)] = (recorded[shaKey(row.id, it)] ?: 0) + 1 }
            row.contentShaNorm?.let { recorded[normKey(row.id, it)] = (recorded[normKey(row.id, it)] ?: 0) + 1 }
        }
        return Budget(recorded)
    }

    /** An AI edit that recorded THIS content at THIS exact line. Consumes one occurrence. */
    private fun pickExactAiEdit(filePath: String, ln: Int, lineText: String, edits: List<CliEditRow>, b: Budget): CliEditRow? {
        val normFile = filePath.replace('\\', '/')
        val text = lineText.removeSuffix("\r")
        val hash = lineSha(text)
        val normHash = lineShaNorm(text)
        for (row in edits) {
            if (row.filePath.replace('\\', '/') != normFile) continue
            if (!isLineAttributionCandidate(row)) continue
            if (row.startLine != ln) continue
            if (row.contentSha != null && row.contentSha == hash) { consumeBudget(b, shaKey(row.id, hash)); return row }
            if (normHash.isNotEmpty() && row.contentShaNorm != null && row.contentShaNorm == normHash) { consumeBudget(b, normKey(row.id, normHash)); return row }
        }
        return null
    }

    /** A drift match plus whether it consumed a real recorded occurrence (budgeted)
     *  or fell back to the nearest match after the budget was exhausted. The
     *  copy-paste guard only fires on the latter. */
    private data class DriftMatch(val row: CliEditRow, val budgeted: Boolean)

    /**
     * An AI edit whose content matches this line but at a DIFFERENT position (drift).
     * Prefers an edit that still has an unconsumed occurrence (nearest among those),
     * so identical content distributes across the edits that recorded it; falls back
     * to the nearest match overall when every budget is spent.
     *
     * Returns budgeted=true when the match consumed a real, unconsumed recorded
     * occurrence — the AI genuinely wrote this many copies of the content — and
     * budgeted=false when every occurrence was already spent and we fell back to
     * the nearest match (a candidate for the copy-paste guard). Range-only matches
     * (no content_sha) report budgeted=true: covered by range, not a content budget.
     */
    private fun pickDriftAiEdit(filePath: String, ln: Int, lineText: String, edits: List<CliEditRow>, b: Budget): DriftMatch? {
        val normFile = filePath.replace('\\', '/')
        val text = lineText.removeSuffix("\r")
        val hash = lineSha(text)

        var best: CliEditRow? = null; var bestDrift = Int.MAX_VALUE
        var budgeted: CliEditRow? = null; var budgetedDrift = Int.MAX_VALUE
        for (row in edits) {
            if (row.filePath.replace('\\', '/') != normFile) continue
            if (!isLineAttributionCandidate(row)) continue
            if (row.contentSha == null || row.contentSha != hash) continue
            val drift = kotlin.math.abs(row.startLine - ln)
            if (drift < bestDrift) { bestDrift = drift; best = row }
            if (budgetLeft(b, shaKey(row.id, hash)) && drift < budgetedDrift) { budgetedDrift = drift; budgeted = row }
        }
        budgeted?.let { consumeBudget(b, shaKey(it.id, hash)); return DriftMatch(it, true) }
        best?.let { consumeBudget(b, shaKey(it.id, hash)); return DriftMatch(it, false) }

        val normHash = lineShaNorm(text)
        if (normHash.isNotEmpty()) {
            var bn: CliEditRow? = null; var bnDrift = Int.MAX_VALUE
            var bnBudgeted: CliEditRow? = null; var bnBudgetedDrift = Int.MAX_VALUE
            for (row in edits) {
                if (row.filePath.replace('\\', '/') != normFile) continue
                if (!isLineAttributionCandidate(row)) continue
                if (row.contentShaNorm == null || row.contentShaNorm != normHash) continue
                val drift = kotlin.math.abs(row.startLine - ln)
                if (drift < bnDrift) { bnDrift = drift; bn = row }
                if (budgetLeft(b, normKey(row.id, normHash)) && drift < bnBudgetedDrift) { bnBudgetedDrift = drift; bnBudgeted = row }
            }
            bnBudgeted?.let { consumeBudget(b, normKey(it.id, normHash)); return DriftMatch(it, true) }
            bn?.let { consumeBudget(b, normKey(it.id, normHash)); return DriftMatch(it, false) }
        }

        // Range-only edits (no content_sha) cover a line by range — no content budget.
        for (row in edits) {
            if (row.filePath.replace('\\', '/') != normFile) continue
            if (!isLineAttributionCandidate(row)) continue
            if (row.contentSha != null) continue
            if (!rowCoversLine(row, ln)) continue
            if (!isIndexableLineRange(row)) continue
            return DriftMatch(row, true)
        }
        return null
    }

    // Max line drift for untracked-file fallback.
    private val MAX_CONTENT_SHA_DRIFT = 200

    private fun applyContentShaAttribution(
        repoRoot: String,
        edits: List<CliEditRow>,
        filePaths: Collection<String>,
        byFile: MutableMap<String, List<LineBlame>>,
        untrackedFiles: Set<String> = emptySet(),
    ) {
        // Two-pass attribution:
        //
        // Pass 1 (all files): line-number-first. byLine[N] exists and SHA matches → AI.
        // Prevents a human-added `}` at line 247 from matching the AI row at line 10.
        //
        // Pass 2 (untracked files only): drift fallback. A human Enter-press above AI
        // content shifts every subsequent line by 1; those lines fail pass 1 because
        // their recorded position is now off by one. Re-locate by content SHA, but only
        // attribute when the original position no longer holds that content (i.e. the
        // line genuinely drifted, not a human copy with the original still in place).
        val lineByFile = mutableMapOf<String, MutableMap<Int, CliEditRow>>()
        // Store ALL rows per SHA (not just the newest) so the drift lookup can pick
        // the row whose startLine is closest to the current line being evaluated.
        val shaByFile  = mutableMapOf<String, MutableMap<String, MutableList<CliEditRow>>>()
        for (row in edits) {
            if (row.contentSha == null) continue
            if (!isAiEditRow(row)) continue
            val file = row.filePath.replace('\\', '/')
            val byLine = lineByFile.getOrPut(file) { mutableMapOf() }
            if (!byLine.containsKey(row.startLine)) byLine[row.startLine] = row
            val bySha = shaByFile.getOrPut(file) { mutableMapOf() }
            bySha.getOrPut(row.contentSha) { mutableListOf() }.add(row)
        }
        for (filePath in filePaths) {
            val norm = filePath.replace('\\', '/')
            val byLine = lineByFile[norm] ?: continue
            val isUntracked = norm in untrackedFiles
            val bySha = if (isUntracked) shaByFile[norm] else null
            val lines = try {
                File(repoRoot, norm).readLines()
            } catch (_: Exception) {
                continue
            }
            val entries = byFile[norm]?.toMutableList() ?: mutableListOf()
            // Per-content occurrence budget (mirrors VS Code CliDataService): a shifted
            // duplicate is a REAL drifted AI line while the recorded copies last; only
            // copies BEYOND the recorded count are human copies. Without this, a run of
            // identical AI lines partly shifted by a human insert (e.g. 5 lines pushed
            // down 2) splits into AI + Human, disagreeing with the commit note.
            val shaConsumed = mutableMapOf<String, Int>()
            var mutated = false
            for (ln in lines.indices) {
                val lineNumber = ln + 1
                val text = lines[ln]
                if (BlankLines.isBlankLine(text)) continue
                val sha = lineSha(text.removeSuffix("\r"))

                // Pass 1: exact position + content confirmation.
                val exactRow = byLine[lineNumber]
                val row: CliEditRow? = when {
                    exactRow != null && sha == exactRow.contentSha -> {
                        shaConsumed[sha] = (shaConsumed[sha] ?: 0) + 1
                        exactRow
                    }
                    exactRow?.contentShaNorm != null &&
                        lineShaNorm(text.removeSuffix("\r")) == exactRow.contentShaNorm -> {
                        // Autoformatter reflowed this line's whitespace (reindent,
                        // trailing whitespace) after the AI wrote it: exact content_sha
                        // no longer matches but content_sha_norm still does.
                        exactRow
                    }
                    bySha != null -> {
                        // Pass 2: drift fallback (untracked files only).
                        // Pick the candidate whose startLine is closest to lineNumber:
                        // when two AI edits share the same content (e.g. `}`), the
                        // closest one is the most likely origin of the drifted line.
                        val candidates = bySha[sha]
                        val driftRow = candidates?.minByOrNull { kotlin.math.abs(it.startLine - lineNumber) }
                        if (driftRow != null && kotlin.math.abs(lineNumber - driftRow.startLine) <= MAX_CONTENT_SHA_DRIFT) {
                            val used = shaConsumed[sha] ?: 0
                            if (used < (candidates?.size ?: 0)) {
                                // Genuine recorded occurrence, just shifted — attribute it
                                // and skip the copy-paste guard (which would wrongly reject
                                // a real duplicate whose recorded home still holds a copy).
                                shaConsumed[sha] = used + 1
                                driftRow
                            } else {
                                // Budget exhausted: a copy beyond the recorded count. If the
                                // recorded position still holds the content, it's a human
                                // copy, not a drift → leave Human.
                                val origIdx = driftRow.startLine - 1
                                val origStillAtHome = origIdx in lines.indices &&
                                    lineSha(lines[origIdx].removeSuffix("\r")) == sha
                                if (!origStillAtHome) {
                                    shaConsumed[sha] = used + 1
                                    driftRow
                                } else null
                            }
                        } else null
                    }
                    else -> null
                }
                if (row == null) continue

                val entry = buildLineBlame(repoRoot, norm, lineNumber, row, contentShaAttributed = true)
                val idx = entries.indexOfFirst { it.lineNumber == lineNumber }
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

            // Resolve AI attribution with a per-edit occurrence budget so identical
            // content recorded by several edits is distributed by recorded count
            // (matching the commit report) instead of all going to the nearest edit.
            val budget = buildBudget(filePath.replace('\\', '/'), edits)
            val chosen = HashMap<Int, CliEditRow?>()
            // Pass 1: exact-position matches — unambiguous; they consume their
            // occurrence so a drifted duplicate can't steal it in pass 2.
            for (ln in lineNums) {
                val text = lines.getOrNull(ln - 1) ?: continue
                val row = pickExactAiEdit(filePath, ln, text, edits, budget)
                if (row != null) chosen[ln] = row
            }
            // Pass 2: drifted lines — budgeted nearest match, then the copy-paste guard.
            for (ln in lineNums) {
                if (chosen.containsKey(ln)) continue
                val text = lines.getOrNull(ln - 1) ?: continue
                val drift = pickDriftAiEdit(filePath, ln, text, edits, budget)
                var aiRow = drift?.row

                // Copy-paste guard: content found at a different line than recorded.
                // If the original position still holds that content, this is a human
                // copy — not the AI line drifting. Clear aiRow so it shows Human.
                //
                // Skip the guard when the match was a BUDGETED occurrence: the AI
                // genuinely recorded this many copies of the content (e.g. it wrote 5
                // identical lines), so a duplicate that shifted is a real AI line, not
                // a human copy. Firing the guard there splits a run of identical AI
                // lines into AI+Human and disagrees with the commit note (which rations
                // the same drift budget). The guard is only for copies BEYOND the
                // recorded count — when every occurrence is spent and we fell back to
                // the nearest match (drift.budgeted == false).
                // matchedByNorm distinguishes a content_sha_norm drift match (e.g. an
                // autoformatter-reflowed AI line whose shape was duplicated elsewhere)
                // from a content_sha exact drift match, so the guard re-checks the
                // recorded position with the SAME hash that produced the match.
                val row = aiRow
                if (row != null && drift != null && !drift.budgeted && row.startLine != ln) {
                    val lineHash = lineSha(text.removeSuffix("\r"))
                    val lineNormHash = lineShaNorm(text.removeSuffix("\r"))
                    val matchedByNorm = row.contentSha != lineHash && row.contentShaNorm == lineNormHash
                    val origIdx = row.startLine - 1
                    val origLine = if (origIdx in lines.indices) lines[origIdx].removeSuffix("\r") else null
                    val origStillAtHome = when {
                        origLine == null -> false
                        matchedByNorm -> row.contentShaNorm != null && lineShaNorm(origLine) == row.contentShaNorm
                        row.contentSha != null -> lineSha(origLine) == row.contentSha
                        else -> false
                    }
                    if (origStillAtHome) aiRow = null
                }
                chosen[ln] = aiRow
            }

            for (ln in lineNums) {
                val text = lines.getOrNull(ln - 1) ?: continue
                val idx = entries.indexOfFirst { it.lineNumber == ln }
                val existing = if (idx >= 0) entries[idx] else null
                val pending = blameService.pendingAiLinesFor(filePath)[ln]

                val aiRow = chosen[ln]

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
