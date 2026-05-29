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
                val byFile = editsToBlameMap(repoRoot, edits).toMutableMap()

                // Add human LineBlame entries for working-tree lines not attributed to AI.
                // Empty after commit → human resets to 0. Fills in as user types.
                val humanLinesByFile = getWorkingTreeHumanLines(repoRoot)
                for ((filePath, lineNums) in humanLinesByFile) {
                    val existing = byFile.getOrDefault(filePath, emptyList())
                    val aiLineSet = existing.mapTo(HashSet()) { it.lineNumber }
                    val humanEntries = lineNums.filter { ln -> ln !in aiLineSet }.map { ln ->
                        LineBlame(
                            lineNumber = ln,
                            authorType = LineBlame.AuthorType.HUMAN,
                            timestamp = Instant.now().toString(),
                            aiChars = 0,
                            humanChars = 1,
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
                        val aiLineSet = existing.mapTo(HashSet()) { it.lineNumber }
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
                    val lines = result.getOrPut(currentFile!!) { mutableListOf() }
                    for (i in 0 until count) if (start + i > 0) lines.add(start + i)
                }
            }
        }
        return result
    }

    private fun checkDaemonHealth() {
        daemonStatus = CliHealth.check().daemon
    }

    private fun editsToBlameMap(repoRoot: String, edits: List<CliEditRow>): Map<String, List<LineBlame>> {
        val assigned = mutableMapOf<String, MutableMap<Int, CliEditRow>>()
        val fileLineCounts = mutableMapOf<String, Int?>()
        for (row in edits) {
            val byLine = assigned.getOrPut(row.filePath) { mutableMapOf() }
            val fileLines = fileLineCounts.getOrPut(row.filePath) {
                try { File(repoRoot, row.filePath).readLines().size } catch (_: Exception) { null }
            }
            val hardMax = 50_000
            val cappedEnd = minOf(row.endLine, fileLines ?: hardMax, hardMax)
            for (ln in row.startLine..cappedEnd) {
                byLine.putIfAbsent(ln, row)
            }
        }
        return assigned.mapValues { (file, byLine) ->
            byLine.entries.sortedBy { it.key }.map { (ln, row) ->
                buildLineBlame(repoRoot, file, ln, row)
            }
        }
    }

    private fun buildLineBlame(repoRoot: String, filePath: String, lineNumber: Int, row: CliEditRow): LineBlame {
        val ai = CliSqliteReader.isAiTool(row.tool)
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
