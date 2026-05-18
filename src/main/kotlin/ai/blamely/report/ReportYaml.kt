package ai.blamely.report

import ai.blamely.core.BlameMap
import ai.blamely.core.LineBlame
import ai.blamely.core.TraceStore
import ai.blamely.git.GitUtils
import ai.blamely.utils.Platform
import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Metrics for the report: first start coding time, time waiting for AI.
 */
data class ReportMetrics(
    val firstStartCodingTimeMs: Long = 0L,
    val timeWaitingForAiMs: Long = 0L
)

/** Aggregated counts for tests / parity (hookRunner reads snapshots, not a sidecar file). */
data class HookTotals(
    val aiLinesAdded: Int,
    val aiLinesDeleted: Int,
    val humanLinesAdded: Int,
    val humanLinesDeleted: Int
)

/**
 * Generates report.yml content for a commit. Same format as Blamely VS Code ReportYaml.ts.
 */
object ReportYaml {

    private const val DETECTOR_VERSION = "0.1.2"
    private val log = Logger.getInstance(ReportYaml::class.java)
    private val gson = Gson()

    fun computeHookTotalsFromBlameSnapshot(entireBlame: Map<String, List<LineBlame>>): HookTotals {
        var aa = 0
        var ad = 0
        var ha = 0
        var hd = 0
        for ((_, entries) in entireBlame) {
            for (e in entries) {
                when (e.changeType) {
                    LineBlame.ChangeType.ADD ->
                        when (e.authorType) {
                            LineBlame.AuthorType.AI -> aa++
                            LineBlame.AuthorType.HUMAN -> ha++
                        }
                    LineBlame.ChangeType.DELETE ->
                        when (e.authorType) {
                            LineBlame.AuthorType.AI -> ad++
                            LineBlame.AuthorType.HUMAN -> hd++
                        }
                }
            }
        }
        return HookTotals(aa, ad, ha, hd)
    }

    /** Header lines parsed by bundled `hookRunner.js`. */
    fun detectorHookPreamble(totals: HookTotals): String {
        val aiTotal = totals.aiLinesAdded + totals.aiLinesDeleted
        val humanTotal = totals.humanLinesAdded + totals.humanLinesDeleted
        val all = aiTotal + humanTotal
        val aiPct = if (all > 0) "%.1f".format(100.0 * aiTotal / all) else "0.0"
        val humanPct = if (all > 0) "%.1f".format(100.0 * humanTotal / all) else "0.0"
        return "# AI-authored lines: $aiTotal ($aiPct%)\n" +
            "# Human-authored lines: $humanTotal ($humanPct%)\n" +
            "# ai_lines_added: ${totals.aiLinesAdded}\n" +
            "# ai_lines_deleted: ${totals.aiLinesDeleted}\n" +
            "# human_lines_added: ${totals.humanLinesAdded}\n" +
            "# human_lines_deleted: ${totals.humanLinesDeleted}\n\n"
    }

    /** Serialize blame snapshot for report in YAML format (excludes aiChars and humanChars). */
    fun blameSnapshotToYaml(entireBlame: Map<String, List<LineBlame>>): String {
        val sb = StringBuilder()
        for ((filePath, entries) in entireBlame) {
            sb.append("  \"${filePath.replace("\\", "\\\\").replace("\"", "\\\"")}\":\n")
            if (entries.isEmpty()) {
                sb.append("    []\n")
                continue
            }
            for (e in entries) {
                val ln =
                    if (e.changeType == LineBlame.ChangeType.DELETE) e.oldLineNumber ?: e.lineNumber
                    else e.newLineNumber ?: e.lineNumber
                sb.append("    - lineNumber: $ln\n")
                sb.append("      authorType: \"${e.authorType.name}\"\n")
                sb.append("      model: ${yamlStr(e.model)}\n")
                sb.append("      date: ${if (e.timestamp.isBlank()) "null" else yamlStr(e.timestamp)}\n")
                if (!e.prompt.isNullOrBlank()) sb.append("      prompt: ${yamlStr(e.prompt)}\n")
                if (!e.interactionType.isNullOrBlank()) sb.append("      interactionType: ${yamlStr(e.interactionType)}\n")
                sb.append("      changeType: \"${e.changeType.name}\"\n")
                sb.append("      codingType: \"${e.codingType.name}\"\n")
            }
        }
        return sb.toString()
    }

    private fun yamlStr(value: String?): String {
        if (value == null) return "null"
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "\"$escaped\""
    }

    fun generate(
        project: Project,
        blameMap: BlameMap,
        traceStore: TraceStore,
        commitHash: String? = null,
        ideName: String = "IntelliJ"
    ): String {
        return try {
            buildLiveYamlPayload(project, blameMap, traceStore, commitHash, ideName)?.yaml ?: ""
        } catch (e: Exception) {
            log.error("Failed to generate ReportYaml string", e)
            ""
        }
    }

    /**
     * Generates report YAML from live blame (`commitSha == null`), merges staged `git diff --cached`
     * deletions (VS Code parity), persists only `report.yml` under ~/.blamely/repos/<repoKey>/branches/<branch>/.
     */
    fun generateAndPersistDetector(
        project: Project,
        blameMap: BlameMap,
        traceStore: TraceStore,
        commitHash: String? = null,
        ideName: String = "IntelliJ"
    ): String {
        return try {
            val payload = buildLiveYamlPayload(project, blameMap, traceStore, commitHash, ideName) ?: return ""
            payload.yaml
        } catch (e: Exception) {
            log.error("Failed to generate report", e)
            ""
        }
    }

    private data class LiveYamlPayload(val yaml: String, val hookTotals: HookTotals)

    /**
     * Live report: uncommitted lines only unless [commitHash] is set; merges staged deletions so totals match the index.
     */
    private fun buildLiveYamlPayload(
        project: Project,
        blameMap: BlameMap,
        traceStore: TraceStore,
        commitHash: String?,
        ideName: String
    ): LiveYamlPayload? {
        if (project.basePath == null) return null
        val snapshot = buildFilteredBlameSnapshot(blameMap, commitHash)
        mergeStagedDeletionsIntoSnapshot(project, blameMap, snapshot, java.time.Instant.now().toString())
        val headSha = commitHash ?: GitUtils.getLatestCommitSha(project) ?: "unknown"
        val metricsFromMap = ReportMetrics(
            firstStartCodingTimeMs = blameMap.firstStartCodingTimeMs,
            timeWaitingForAiMs = blameMap.totalTimeWaitingForAiMs
        )
        val yaml = generateContentFromSnapshot(project, snapshot, traceStore, headSha, ideName, metricsFromMap)
        val totals = computeHookTotalsFromBlameSnapshot(snapshot)
        return LiveYamlPayload(yaml, totals)
    }

    private fun buildFilteredBlameSnapshot(
        blameMap: BlameMap,
        commitHash: String?
    ): MutableMap<String, MutableList<LineBlame>> {
        val explicit = commitHash != null && commitHash.isNotBlank() && commitHash != "unknown"
        val out = linkedMapOf<String, MutableList<LineBlame>>()
        for (filePath in blameMap.getTrackedFiles()) {
            val filtered = blameMap.getBlame(filePath).filter { e ->
                if (explicit) e.commitSha == commitHash else e.commitSha == null
            }
            if (filtered.isNotEmpty()) {
                out[filePath] = filtered.toMutableList()
            }
        }
        return out
    }

    /**
     * DELETE rows are dropped from the live blame map when lines move; merge staged index deletions so
     * hook totals align with `git diff --cached` (same behavior as Blamely VS Code).
     */
    private fun mergeStagedDeletionsIntoSnapshot(
        project: Project,
        blameMap: BlameMap,
        blameSnapshot: MutableMap<String, MutableList<LineBlame>>,
        timestampIso: String
    ) {
        val repoRoot = GitUtils.getRepoRoot(project) ?: return
        val basePath = project.basePath ?: return
        for (repoRel in GitUtils.listStagedRepoRelativePaths(repoRoot)) {
            val stats = GitUtils.getStagedDiffStats(repoRoot, repoRel)
            if (stats.deletedCount == 0) continue
            val projectRelSet = GitUtils.repoRelativeToProjectRelative(repoRoot, basePath, listOf(repoRel))
            val blameKey = Platform.normalizePath(projectRelSet.singleOrNull() ?: continue)
            val deleteRows = mutableListOf<LineBlame>()
            for (oldLine in stats.deletedLines.sorted()) {
                val deletedByAi = blameMap.wasLineDeletedByAi(blameKey, oldLine)
                deleteRows.add(
                    LineBlame(
                        lineNumber = oldLine,
                        authorType = if (deletedByAi) LineBlame.AuthorType.AI else LineBlame.AuthorType.HUMAN,
                        provider = null,
                        timestamp = timestampIso,
                        commitSha = null,
                        model = if (deletedByAi) "unknown" else null,
                        prompt = null,
                        interactionType = null,
                        aiChars = if (deletedByAi) 1 else 0,
                        humanChars = if (deletedByAi) 0 else 1,
                        changeType = LineBlame.ChangeType.DELETE,
                        newLineNumber = null,
                        oldLineNumber = oldLine
                    )
                )
            }
            val prev = blameSnapshot[blameKey]?.toMutableList()
                ?: blameMap.getBlame(blameKey).filter { it.commitSha == null }.toMutableList()
            val seenOld = prev.filter { it.changeType == LineBlame.ChangeType.DELETE && it.oldLineNumber != null }
                .mapNotNullTo(mutableSetOf()) { it.oldLineNumber }
            for (row in deleteRows) {
                val ol = row.oldLineNumber
                if (ol != null && ol !in seenOld) {
                    prev.add(row)
                    seenOld.add(ol)
                }
            }
            blameSnapshot[blameKey] = prev
        }
    }

    /** Build report from a pre-built blame snapshot (e.g. tracker + synthetic human). */
    fun generateFromBlameSnapshot(
        project: Project,
        entireBlame: Map<String, List<LineBlame>>,
        traceStore: TraceStore,
        commitHash: String?,
        ideName: String = "IntelliJ",
        metrics: ReportMetrics? = null
    ): String {
        return try {
            generateContentFromSnapshot(project, entireBlame, traceStore, commitHash, ideName, metrics)
        } catch (e: Exception) {
            log.error("Failed to generate ReportYaml from snapshot", e)
            ""
        }
    }

    private fun generateContentFromSnapshot(
        project: Project,
        entireBlame: Map<String, List<LineBlame>>,
        @Suppress("UNUSED_PARAMETER") traceStore: TraceStore,
        commitHash: String?,
        ideName: String,
        metrics: ReportMetrics? = null
    ): String {
        if (project.basePath == null) return ""
        val generatedAt = java.time.Instant.now().toString()
        val branch = GitUtils.getBranch(project) ?: "unknown"
        val finalCommitHash = commitHash ?: GitUtils.getLatestCommitSha(project) ?: "unknown"
        val commitMessage = GitUtils.getCommitMessage(project) ?: "N/A"
        val interactionTypesFromBlame = mutableSetOf<String>()
        val fileEntries = mutableListOf<FileEntry>()
        for ((filePath, entries) in entireBlame) {
            if (entries.isEmpty()) continue
            val addedEntries = entries.filter { it.changeType == LineBlame.ChangeType.ADD }
            val deletedCount = entries.count { it.changeType == LineBlame.ChangeType.DELETE }

            var aiLines = 0
            var humanLines = 0
            val models = mutableSetOf<String>()
            for (e in addedEntries) {
                if (e.authorType == LineBlame.AuthorType.AI) {
                    aiLines++
                    ai.blamely.utils.AiContextExtractor.sanitizeModelForReport(e.model)?.let { m -> models.add(m) }
                    e.interactionType?.takeIf { it.isNotBlank() }?.let { interactionTypesFromBlame.add(it) }
                } else {
                    humanLines++
                }
            }
            var aiLinesDeleted = 0
            var humanLinesDeleted = 0
            for (e in entries.filter { it.changeType == LineBlame.ChangeType.DELETE }) {
                when (e.authorType) {
                    LineBlame.AuthorType.AI -> aiLinesDeleted++
                    LineBlame.AuthorType.HUMAN -> humanLinesDeleted++
                }
            }
            val modelDisplay = when (models.size) {
                0 -> "unknown"
                1 -> models.first()
                else -> "multiple"
            }
            fileEntries.add(
                FileEntry(
                    path = filePath,
                    model = modelDisplay,
                    aiLinesAdded = aiLines,
                    humanLinesAdded = humanLines,
                    aiLinesDeleted = aiLinesDeleted,
                    humanLinesDeleted = humanLinesDeleted,
                    linesDeleted = deletedCount
                )
            )
        }
        return buildReportYaml(
            generatedAt,
            branch,
            finalCommitHash,
            commitMessage,
            fileEntries,
            entireBlame,
            ideName,
            metrics,
            interactionTypesFromBlame
        )
    }

    private fun collectIdesFromBlame(blameByFile: Map<String, List<LineBlame>>, fallbackIde: String): List<String> {
        val s = mutableSetOf<String>()
        for (rows in blameByFile.values) {
            for (e in rows) {
                e.ide?.trim()?.takeIf { it.isNotEmpty() }?.let { s.add(it) }
            }
        }
        val fb = fallbackIde.trim()
        if (fb.isNotEmpty() && !s.contains(fb)) s.add(fb)
        return s.sorted()
    }

    /** Per-line rows under `files[].changes` (matches VS Code `emitChangeRows`). */
    private fun emitChangeRows(entries: List<LineBlame>): String {
        val sorted = entries.sortedWith(
            compareBy { e ->
                if (e.changeType == LineBlame.ChangeType.DELETE) e.oldLineNumber ?: e.lineNumber
                else e.newLineNumber ?: e.lineNumber
            }
        )
        val sb = StringBuilder()
        for (e in sorted) {
            val ln =
                if (e.changeType == LineBlame.ChangeType.DELETE) e.oldLineNumber ?: e.lineNumber
                else e.newLineNumber ?: e.lineNumber
            sb.append("      - lineNumber: $ln\n")
            sb.append("        authorType: \"${e.authorType.name}\"\n")
            sb.append("        model: ${if (e.model.isNullOrBlank()) "null" else yamlStr(e.model)}\n")
            sb.append("        date: ${if (e.timestamp.isBlank()) "null" else yamlStr(e.timestamp)}\n")
            val it = e.interactionType?.takeIf { it.isNotBlank() }
            sb.append("        interactionType: ${if (it != null) yamlStr(it) else "null"}\n")
            sb.append("        changeType: \"${e.changeType.name}\"\n")
            sb.append("        codingType: \"${e.codingType.name}\"\n")
        }
        return sb.toString()
    }

    private fun buildReportYaml(
        generatedAt: String,
        branch: String,
        finalCommitHash: String,
        commitMessage: String,
        fileEntries: List<FileEntry>,
        blameByFile: Map<String, List<LineBlame>>,
        ideName: String,
        metrics: ReportMetrics? = null,
        interactionTypesFromBlame: Set<String> = emptySet()
    ): String {
        val interactionTypes = interactionTypesFromBlame.toMutableSet()
        val sb = StringBuilder()
        sb.append("scope: \"this_commit\"\n")
        sb.append("commitDate: \"$generatedAt\"\n")
        sb.append("detector_version: \"$DETECTOR_VERSION\"\n")
        sb.append("branch: \"$branch\"\n")
        sb.append("commit_hash: \"$finalCommitHash\"\n")
        sb.append("commit_message: ${gson.toJson(commitMessage)}\n\n")

        val totalAiAdded = fileEntries.sumOf { it.aiLinesAdded }
        val totalHumanAdded = fileEntries.sumOf { it.humanLinesAdded }
        val totalAiDeleted = fileEntries.sumOf { it.aiLinesDeleted }
        val totalHumanDeleted = fileEntries.sumOf { it.humanLinesDeleted }
        val totalDeleted = fileEntries.sumOf { it.linesDeleted }
        val totalChanges = totalAiAdded + totalHumanAdded + totalDeleted
        val aiMass = totalAiAdded + totalAiDeleted
        sb.append("summary:\n")
        sb.append("  total_files_changed: ${fileEntries.size}\n")
        sb.append("  total_lines_added: ${totalAiAdded + totalHumanAdded}\n")
        sb.append("  total_lines_deleted: $totalDeleted\n")
        sb.append("  total_changes: $totalChanges\n")
        sb.append("  ai_lines_added: $totalAiAdded\n")
        sb.append("  ai_lines_deleted: $totalAiDeleted\n")
        sb.append("  human_lines_added: $totalHumanAdded\n")
        sb.append("  human_lines_deleted: $totalHumanDeleted\n")
        val overallPct = if (totalChanges > 0) "%.1f".format(100.0 * aiMass / totalChanges) + "%" else "0.0%"
        sb.append("  ai_percentage: \"$overallPct\"\n")
        val allModelsDistinct = fileEntries.map { it.model }.filter { it != "unknown" }.toSet()
        val modelCount = allModelsDistinct.size
        sb.append("  model_count: $modelCount\n\n")

        val m = metrics ?: ReportMetrics()
        sb.append("metrics:\n")
        val firstStartCodingTimeDate = if (m.firstStartCodingTimeMs > 0L) {
            java.time.Instant.ofEpochMilli(m.firstStartCodingTimeMs).toString()
        } else null
        sb.append("  first_start_coding_time: ${if (firstStartCodingTimeDate != null) "\"$firstStartCodingTimeDate\"" else "null"}\n")
        sb.append("  time_waiting_for_ai_ms: ${m.timeWaitingForAiMs}\n\n")

        val ideList = collectIdesFromBlame(blameByFile, ideName)
        sb.append("agent_info:\n")
        sb.append("  ide:\n")
        if (ideList.isEmpty()) {
            sb.append("    []\n")
        } else {
            ideList.forEach { sb.append("    - ${gson.toJson(it)}\n") }
        }
        sb.append("  models:\n")
        if (allModelsDistinct.isEmpty()) {
            sb.append("    []\n")
        } else {
            allModelsDistinct.sorted().forEach { sb.append("    - ${gson.toJson(it)}\n") }
        }
        sb.append("  interaction_types:\n")
        if (interactionTypes.isEmpty()) {
            sb.append("    []\n")
        } else {
            interactionTypes.sorted().forEach { sb.append("    - $it\n") }
        }
        sb.append("\nfiles:\n")
        if (fileEntries.isEmpty()) {
            sb.append("  []\n")
        } else {
            fileEntries.forEach { e ->
                sb.append("  - path: ${gson.toJson(e.path)}\n")
                sb.append("    changes:\n")
                val rows = blameByFile[e.path].orEmpty()
                if (rows.isEmpty()) {
                    sb.append("      []\n")
                } else {
                    sb.append(emitChangeRows(rows))
                }
            }
        }
        return sb.toString()
    }

    private data class FileEntry(
        val path: String,
        val model: String,
        val aiLinesAdded: Int,
        val humanLinesAdded: Int,
        val aiLinesDeleted: Int,
        val humanLinesDeleted: Int,
        val linesDeleted: Int
    )
}
