package ai.blamely.cli

import ai.blamely.core.BlameMap
import ai.blamely.core.BlameMapService
import ai.blamely.core.BlameUpdateListener
import ai.blamely.core.LineBlame
import ai.blamely.git.GitUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
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
                val repoId = CliRepoId.get(repoRoot) ?: return@executeOnPooledThread
                checkDaemonHealth()
                val edits = CliSqliteReader.loadEditsForRepo(repoId)
                val byFile = editsToBlameMap(repoRoot, edits)
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

    private fun checkDaemonHealth() {
        daemonStatus = CliHealth.check().daemon
    }

    private fun editsToBlameMap(repoRoot: String, edits: List<CliEditRow>): Map<String, List<LineBlame>> {
        val assigned = mutableMapOf<String, MutableMap<Int, CliEditRow>>()
        for (row in edits) {
            val byLine = assigned.getOrPut(row.filePath) { mutableMapOf() }
            for (ln in row.startLine..row.endLine) {
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
        return try {
            val lines = File(repoRoot, filePath).readText().split("\r\n", "\n")
            if (lineNumber < 1 || lineNumber > lines.size) 1
            else (lines[lineNumber - 1].length).coerceAtLeast(1)
        } catch (_: Exception) {
            1
        }
    }
}
