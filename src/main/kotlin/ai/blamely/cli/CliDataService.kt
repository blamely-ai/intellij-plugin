package ai.blamely.cli

import ai.blamely.core.BlameMap
import ai.blamely.core.BlameMapService
import ai.blamely.core.BlameUpdateListener
import ai.blamely.core.LineBlame
import ai.blamely.git.GitUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.Alarm
import java.io.File
import java.time.Instant

/**
 * Read-only bridge to oobeya-cli runtime data (~/.blamely/db.sqlite).
 */
@Service(Service.Level.PROJECT)
class CliDataService(private val project: Project) {
    @Volatile
    var daemonStatus: DaemonStatus = DaemonStatus(running = false)
        private set

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)

    private fun normalizedGenType(genType: String?): String = genType?.trim()?.lowercase() ?: ""
    private fun isInlineCompletionType(genType: String?): Boolean = normalizedGenType(genType) == "completion"
    private fun isAiInteractionType(genType: String?): Boolean = LineBlame.isAiInteractionType(genType)
    private fun hasBoundedRange(row: CliEditRow): Boolean =
        row.endLine >= row.startLine && row.endLine - row.startLine <= 500

    fun start() {
        refresh()
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        if (project.isDisposed) return
        alarm.addRequest(
            {
                if (!project.isDisposed) {
                    refresh()
                    scheduleRefresh()
                }
            },
            2000
        )
    }

    fun refresh() {
        if (project.isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            try {
                val repoRoot = GitUtils.getRepoRoot(project) ?: project.basePath ?: return@executeOnPooledThread
                checkDaemonHealth()

                // Session filter: exclude AI edits before the last commit so attribution
                // resets to 0 after commit. ts in SQLite is nanoseconds.
                val lastCommitSec = GitUtils.run(repoRoot, "log", "-1", "--format=%ct")?.trim()?.toLongOrNull() ?: 0L
                val sinceTs = lastCommitSec * 1_000_000_000L

                val edits = CliSqliteReader.loadEditsForRepo(repoRoot, sinceTs)

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
                saveDirtyDocumentsForFiles(repoRoot, edits.map { it.filePath }.toSet())

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

                // CONSTRAIN AI attribution to lines that truly changed vs HEAD.
                //
                // Chat/CLI applies store a wide range (often the whole file) but only a
                // few lines actually differ — the diff-check strips the unchanged ones.
                //
                // Inline COMPLETIONS are exempt: narrowedBand already records the exact
                // inserted lines, so there is nothing to strip. Applying the diff-check
                // to completions causes false-negatives when the file hasn't been saved
                // yet (git diff sees no change → changed = null → AI removed → Human).
                for ((filePath, entries) in byFile.toList()) {
                    if (filePath in untrackedSet) continue
                    val changed = changedSets[filePath]
                    byFile[filePath] = entries.filter { e ->
                        e.authorType != LineBlame.AuthorType.AI ||
                        isInlineCompletionType(e.interactionType) ||
                        (changed?.contains(e.lineNumber) == true)
                    }
                }

                // Add human LineBlame entries for changed lines not attributed to AI.
                for ((filePath, lineNums) in humanLinesByFile) {
                    val existing = byFile.getOrDefault(filePath, emptyList())
                    val aiLineSet = existing
                        .filter { it.effectiveAuthorType() == LineBlame.AuthorType.AI }
                        .mapTo(HashSet()) { it.lineNumber }
                    val humanEntries = lineNums.filter { ln -> ln !in aiLineSet }.map { ln ->
                        LineBlame(
                            lineNumber = ln,
                            authorType = LineBlame.AuthorType.HUMAN,
                            timestamp = Instant.now().toString(),
                            aiChars = 0,
                            humanChars = lineCharCount(repoRoot, filePath, ln),
                        )
                    }
                    if (humanEntries.isNotEmpty()) {
                        byFile[filePath] = existing + humanEntries
                    }
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
                        val existing = byFile[fp]!!
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
                            if (humanEntries.isNotEmpty()) {
                                byFile[fp] = existing + humanEntries
                            }
                        } catch (_: Exception) {}
                    }
                }

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    val blameMap = project.getService(BlameMapService::class.java).blameMap
                    blameMap.clear()
                    byFile.forEach { (path, entries) -> blameMap.setFileBlame(path, entries) }
                    project.messageBus.syncPublisher(BlameUpdateListener.TOPIC).blameUpdated()
                }
            } catch (_: Exception) {
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

    // CONTENT lines (chat applies) carry a per-line content_sha: a current line is
    // attributed to that edit only if its content still hashes to the same value,
    // so human-typed lines inside an AI region aren't mis-credited (and it
    // survives line shifts). RANGE lines (no sha) are attributed by line number.
    private fun editsToBlameMap(repoRoot: String, edits: List<CliEditRow>): Map<String, List<LineBlame>> {
        val assigned = mutableMapOf<String, MutableMap<Int, CliEditRow>>()
        val contentEdits = mutableMapOf<String, MutableMap<String, CliEditRow>>()
        val fileLineCounts = mutableMapOf<String, Int?>()
        for (row in edits) {
            val sha = row.contentSha
            if (sha != null) {
                val byHash = contentEdits.getOrPut(row.filePath) { mutableMapOf() }
                if (!byHash.containsKey(sha)) byHash[sha] = row
                if (!isAiInteractionType(row.genType) || !hasBoundedRange(row)) continue
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
        for ((file, shaMap) in contentEdits) {
            val lines = try { File(repoRoot, file).readLines() } catch (_: Exception) { continue }
            val entries = result.getOrPut(file) { mutableListOf() }
            val claimed = entries.mapTo(HashSet()) { it.lineNumber }
            for ((i, text) in lines.withIndex()) {
                val ln = i + 1
                if (text.isBlank() || ln in claimed) continue
                val row = shaMap[lineSha(text.removeSuffix("\r"))] ?: continue
                entries.add(buildLineBlame(repoRoot, file, ln, row))
            }
        }
        return result.mapValues { (_, entries) -> entries.sortedBy { it.lineNumber } }
    }

    private fun buildLineBlame(repoRoot: String, filePath: String, lineNumber: Int, row: CliEditRow): LineBlame {
        // Treat explicit AI interaction types as AI even if tool labeling drifts.
        // This prevents inline completions/chat edits from rendering as Human.
        val ai = CliSqliteReader.isAiTool(row.tool) || isAiInteractionType(row.genType)
        val chars = lineCharCount(repoRoot, filePath, lineNumber)
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
        )
    }

    private fun lineCharCount(repoRoot: String, filePath: String, lineNumber: Int): Int {
        val abs = File(repoRoot, filePath)
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(abs)
        if (vFile != null) {
            val doc = FileDocumentManager.getInstance().getDocument(vFile)
            if (doc != null && lineNumber in 1..doc.lineCount) {
                val start = doc.getLineStartOffset(lineNumber - 1)
                val end = doc.getLineEndOffset(lineNumber - 1)
                return (end - start).coerceAtLeast(1)
            }
        }
        return try {
            val lines = abs.readText().split("\r\n", "\n")
            if (lineNumber < 1 || lineNumber > lines.size) 1
            else (lines[lineNumber - 1].length).coerceAtLeast(1)
        } catch (_: Exception) {
            1
        }
    }
}
