package ai.blamely.cli

import ai.blamely.core.BlameMap
import ai.blamely.core.LineBlame
import ai.blamely.git.GitUtils
import ai.blamely.persistence.BlameSerializer
import ai.blamely.persistence.BlamelyUserRepoPaths
import ai.blamely.utils.BlamelyLogger
import ai.blamely.utils.Platform
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.io.File

private data class ReportFileEntry(
    val path: String,
    val aiLinesAdded: Int,
    val humanLinesAdded: Int
)

/** Best-effort YAML parse for CLI `report.yml` file rows (mirrors VS Code CliTraceToBlame). */
private fun parseReportYaml(content: String): List<ReportFileEntry> {
    val entries = mutableListOf<ReportFileEntry>()
    var path: String? = null
    var ai = 0
    var hum = 0
    var inChanges = false
    var pendingAuthor: String? = null
    var pendingChangeType: String? = null

    fun flushPendingChange() {
        val p = pendingAuthor
        val ct = (pendingChangeType ?: "ADD").uppercase()
        if (ct == "ADD" && p != null) {
            when (p.uppercase()) {
                "AI" -> ai++
                "HUMAN" -> hum++
            }
        }
        pendingAuthor = null
        pendingChangeType = null
    }

    fun flushFile() {
        flushPendingChange()
        val p = path ?: return
        entries.add(ReportFileEntry(p, ai, hum))
        path = null
        ai = 0
        hum = 0
        inChanges = false
    }

    for (line in content.lineSequence()) {
        val pathMatch = Regex("""^\s+-\s+path:\s+"?([^"\n]+)"?\s*$""").find(line)
        if (pathMatch != null) {
            flushFile()
            path = pathMatch.groupValues[1].trim()
            continue
        }
        if (path == null) continue

        if (Regex("""^\s+changes:\s*\[\s*\]\s*$""").containsMatchIn(line)) {
            flushPendingChange()
            inChanges = false
            continue
        }
        if (Regex("""^\s+changes:\s*$""").containsMatchIn(line)) {
            flushPendingChange()
            inChanges = true
            continue
        }
        if (inChanges && Regex("""^\s+\[\s*\]\s*$""").containsMatchIn(line)) {
            flushPendingChange()
            inChanges = false
            continue
        }

        if (inChanges) {
            if (Regex("""^\s+-\s+lineNumber:\s*\d+""").containsMatchIn(line)) {
                flushPendingChange()
                continue
            }
            val authMatch = Regex("""^\s+authorType:\s*"?([A-Za-z]+)"?\s*$""").find(line)
            if (authMatch != null) {
                pendingAuthor = authMatch.groupValues[1]
                continue
            }
            val changeMatch = Regex("""^\s+changeType:\s*"?([A-Za-z]+)"?\s*$""").find(line)
            if (changeMatch != null) {
                pendingChangeType = changeMatch.groupValues[1]
                continue
            }
            continue
        }

        Regex("""^\s+ai_lines_added:\s+(\d+)""").find(line)?.let { ai = it.groupValues[1].toInt() }
        Regex("""^\s+human_lines_added:\s+(\d+)""").find(line)?.let { hum = it.groupValues[1].toInt() }
    }
    flushFile()
    return entries
}

@Suppress("UNUSED_PARAMETER")
private fun loadReportEntries(session: CliTraceSession, repoRoot: File, layoutRoot: File): Map<String, ReportFileEntry> {
    return emptyMap()
}

private fun blameKey(project: Project, repoRoot: String, repoRelPath: String): String {
    val basePath = project.basePath ?: return Platform.normalizePath(repoRelPath)
    val abs = if (File(repoRelPath).isAbsolute) File(repoRelPath) else File(repoRoot, repoRelPath)
    return try {
        val base = File(basePath).canonicalFile.toPath()
        val target = abs.canonicalFile.toPath()
        val rel = base.relativize(target).toString().replace(File.separatorChar, '/')
        Platform.normalizePath(rel)
    } catch (_: Exception) {
        Platform.normalizePath(repoRelPath)
    }
}

/** Newline splitting aligned with Go bufio.Scanner default line split (strip CR before LF). */
private fun lineCharCountsFromText(text: String): List<Int> {
    val counts = mutableListOf<Int>()
    var start = 0
    var i = 0
    while (i < text.length) {
        if (text[i] != '\n') {
            i++
            continue
        }
        var end = i
        if (end > start && text[end - 1] == '\r') {
            end--
        }
        val line = text.substring(start, end)
        val n = line.codePointCount(0, line.length)
        counts.add(if (n < 1) 1 else n)
        start = i + 1
        i++
    }
    if (start < text.length) {
        var end = text.length
        if (end > start && text[end - 1] == '\r') {
            end--
        }
        val line = text.substring(start, end)
        val n = line.codePointCount(0, line.length)
        counts.add(if (n < 1) 1 else n)
    } else if (counts.isEmpty()) {
        counts.add(1)
    }
    return counts
}

private fun buildLineBlameEntries(
    session: CliTraceSession,
    classification: String,
    charCounts: List<Int>,
    report: ReportFileEntry?
): List<LineBlame> {
    val timestamp = session.endedAt
    val commitSha = session.git?.headAfterTrace?.takeIf { it.isNotBlank() } ?: session.git?.headAtStart
    val model = session.reportModel
    val lineCount = charCounts.size
    val aiLineCount = if (classification.equals("ai", ignoreCase = true)) {
        lineCount
    } else {
        val r = report
        val ratio = if (r != null && r.aiLinesAdded + r.humanLinesAdded > 0) {
            r.aiLinesAdded.toDouble() / (r.aiLinesAdded + r.humanLinesAdded)
        } else {
            0.5
        }
        kotlin.math.round(lineCount * ratio).toInt()
    }
    val out = ArrayList<LineBlame>(lineCount)
    for (lineNum in 1..lineCount) {
        val isAi = lineNum <= aiLineCount
        val ch = charCounts[lineNum - 1]
        out.add(
            LineBlame(
                lineNumber = lineNum,
                newLineNumber = lineNum,
                authorType = if (isAi) LineBlame.AuthorType.AI else LineBlame.AuthorType.HUMAN,
                provider = null,
                timestamp = timestamp,
                commitSha = commitSha,
                model = if (isAi) model else null,
                prompt = null,
                interactionType = if (isAi) session.scope.takeIf { it.isNotBlank() } else null,
                aiChars = if (isAi) ch else 0,
                humanChars = if (isAi) 0 else ch,
                changeType = LineBlame.ChangeType.ADD,
                codingType = LineBlame.CodingType.BULK_INSERT,
                ide = "ai_cli"
            )
        )
    }
    return out
}

object CliTraceToBlame {

    fun populateFromCliSessions(project: Project, blameMap: BlameMap, layoutRoot: File = BlamelyUserRepoPaths.blamelyUserLayoutRoot()) {
        if (project.isDisposed) return
        val repoRootStr = GitUtils.getRepoRoot(project) ?: return
        val repoRoot = File(repoRootStr)
        val currentBranch = GitUtils.getBranch(project) ?: return
        val sessions = CliTraceLoader.loadAll(repoRoot, layoutRoot)
        if (sessions.isEmpty()) return
        var filesAttributed = 0
        for (session in sessions) {
            val sb = session.git?.branch
            if (!sb.isNullOrBlank() && sb != currentBranch) continue
            if (session.files.isEmpty()) continue
            val reportMap = loadReportEntries(session, repoRoot, layoutRoot)
            for (fileEntry in session.files) {
                val repoRel = Platform.normalizePath(fileEntry.path)
                val key = blameKey(project, repoRootStr, repoRel)
                if (blameMap.getBlame(key).isNotEmpty()) continue
                val absPath = if (File(repoRel).isAbsolute) File(repoRel) else File(repoRoot, repoRel)
                val charCounts = try {
                    lineCharCountsFromText(absPath.readText())
                } catch (_: Exception) {
                    continue
                }
                if (charCounts.isEmpty()) continue
                val reportEntry = reportMap[repoRel.lowercase()]
                val entries = buildLineBlameEntries(session, fileEntry.classification, charCounts, reportEntry)
                blameMap.setFileBlame(key, entries)
                try {
                    BlameSerializer.save(project, key, entries)
                } catch (_: Exception) {
                }
                filesAttributed++
            }
        }
        if (filesAttributed > 0) {
            BlamelyLogger.info("Blamely: CLI traces attributed $filesAttributed file(s)")
        }
    }

    fun populateLater(project: Project, blameMap: BlameMap) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            populateFromCliSessions(project, blameMap)
        }
    }
}
