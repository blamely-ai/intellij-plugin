package ai.blamely.persistence

import ai.blamely.core.BlameMap
import ai.blamely.core.LineBlame
import ai.blamely.git.GitUtils
import ai.blamely.utils.BlamelyLogger
import ai.blamely.utils.Platform
import com.google.gson.Gson
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Save/load per-file blame: primary location **`~/.blamely/repos/<id>/snapshots/<branch>/`**
 * (VS Code 1.1.0), with reads/removals also touching `.git/blamely/snapshots/<branch>/` and legacy
 * `~/.blamely/session/` layouts for migration.
 */
object BlameSerializer {

    private val gson = Gson()

    /** Primary snapshot dir (~/.blamely/repos/…). */
    private fun userSnapshotsDir(project: Project, explicitBranch: String? = null): File? {
        val rr = GitUtils.getRepoRoot(project) ?: return null
        val br = explicitBranch ?: GitUtils.getBranch(project)
        return BlamelyUserRepoPaths.blameSnapshotsDir(File(rr), br)
    }

    /** Legacy `.git/blamely/snapshots/<branch>/`. */
    private fun gitSnapshotsDir(project: Project, explicitBranch: String? = null): File? {
        val gd = GitUtils.getGitDir(project) ?: return null
        val br = explicitBranch ?: GitUtils.getBranch(project)
        return BlamelyRepoPaths.snapshotsDir(File(gd), br)
    }

    /** VS Code flat legacy: `~/.blamely/session/<stable8>_<branch>/snapshots`. */
    private fun legacyFlatSnapshots(project: Project, explicitBranch: String? = null): File? {
        val rr = GitUtils.getRepoRoot(project) ?: return null
        val br = explicitBranch ?: GitUtils.getBranch(project)
        return BlamelyUserRepoPaths.legacyFlatSessionSnapshotsDir(File(rr), br)
    }

    /** IntelliJ nested legacy: `~/.blamely/session/<repoKey16>/<branch>/snapshots`. */
    private fun legacyNestedSnapshots(project: Project, explicitBranch: String? = null): File? {
        val rr = GitUtils.getRepoRoot(project) ?: return null
        val canon = BlamelyUserRepoPaths.canonicalRepoDiskPath(rr)
        val rk = BlamelyHomePaths.repoKey(canon)
        val br = explicitBranch ?: GitUtils.getBranch(project)
        val bk = BlamelyRepoPaths.safeBranchName(br)
        return File(BlamelyRepoPaths.legacyBranchDir(rk, bk), "snapshots")
    }

    fun save(project: Project, filePath: String, entries: List<LineBlame>) {
        try {
            val snapshots = userSnapshotsDir(project) ?: return
            if (!snapshots.exists()) snapshots.mkdirs()
            val encoded = Platform.encodeFilePath(filePath) + ".blame.json"
            val target = File(snapshots, encoded)
            target.writeText(serialize(entries))
            BlamelyLogger.info("Saved blame state to ${target.absolutePath}")
        } catch (e: Exception) {
            BlamelyLogger.error("Failed to save blame state for $filePath", e)
        }
    }

    fun load(project: Project, filePath: String): List<LineBlame> {
        val encoded = Platform.encodeFilePath(filePath) + ".blame.json"
        try {
            userSnapshotsDir(project)?.let { dir ->
                val t = File(dir, encoded)
                if (t.exists()) return parse(t.readText())
            }
            legacyFlatSnapshots(project)?.let { dir ->
                val t = File(dir, encoded)
                if (t.exists()) return parse(t.readText())
            }
            legacyNestedSnapshots(project)?.let { dir ->
                val t = File(dir, encoded)
                if (t.exists()) return parse(t.readText())
            }
            gitSnapshotsDir(project)?.let { dir ->
                val t = File(dir, encoded)
                if (t.exists()) return parse(t.readText())
            }
        } catch (e: Exception) {
            BlamelyLogger.warn("Could not load blame state for $filePath: ${e.message}")
        }
        return emptyList()
    }

    fun loadAll(project: Project): Map<String, List<LineBlame>> {
        val candidates = listOfNotNull(
            userSnapshotsDir(project),
            legacyFlatSnapshots(project),
            legacyNestedSnapshots(project),
            gitSnapshotsDir(project)
        ).distinct()
        var chosen: File? = null
        for (dir in candidates) {
            if (!dir.isDirectory) continue
            val files = dir.listFiles()?.filter { it.name.endsWith(".blame.json") }.orEmpty()
            if (files.isNotEmpty()) {
                chosen = dir
                break
            }
        }
        if (chosen == null) return emptyMap()

        val memory = mutableMapOf<String, List<LineBlame>>()
        try {
            chosen.listFiles()?.filter { it.name.endsWith(".blame.json") }?.forEach { f ->
                val relativePath = Platform.decodeFilePath(f.name.removeSuffix(".blame.json"))
                memory[relativePath] = parse(f.readText())
            }
        } catch (e: Exception) {
            BlamelyLogger.error("Failed to load all blame states", e)
        }
        return memory
    }

    /** Delete all persisted blame snapshots for the current branch (primary location). */
    fun clearCurrentBranchSnapshots(project: Project) {
        try {
            val snapshots = userSnapshotsDir(project) ?: return
            if (!snapshots.isDirectory) return
            snapshots.listFiles()?.filter {
                it.name.endsWith(".blame.json") || it.name == "session.json"
            }?.forEach { it.delete() }
            gitSnapshotsDir(project)?.takeIf { it.isDirectory }?.listFiles()?.filter {
                it.name.endsWith(".blame.json") || it.name == "session.json"
            }?.forEach { it.delete() }
        } catch (e: Exception) {
            BlamelyLogger.warn("Could not clear branch snapshots: ${e.message}")
        }
    }

    data class SessionData(
        val first_start_coding_time_ms: Long = 0L,
        val total_time_waiting_for_ai_ms: Long = 0L
    )

    fun saveSession(project: Project, blameMap: BlameMap, explicitBranch: String? = null) {
        try {
            val dir = userSnapshotsDir(project, explicitBranch) ?: return
            if (!dir.exists()) dir.mkdirs()
            val data = SessionData(
                first_start_coding_time_ms = blameMap.firstStartCodingTimeMs,
                total_time_waiting_for_ai_ms = blameMap.totalTimeWaitingForAiMs
            )
            File(dir, "session.json").writeText(gson.toJson(data))
        } catch (e: Exception) {
            BlamelyLogger.warn("Could not save session.json: ${e.message}")
        }
    }

    fun loadSession(project: Project, explicitBranch: String? = null): SessionData {
        return try {
            val encodedBranch = explicitBranch
            userSnapshotsDir(project, encodedBranch)?.let { dir ->
                val f = File(dir, "session.json")
                if (f.exists()) return gson.fromJson(f.readText(), SessionData::class.java) ?: SessionData()
            }
            legacyFlatSnapshots(project, encodedBranch)?.let { dir ->
                val f = File(dir, "session.json")
                if (f.exists()) return gson.fromJson(f.readText(), SessionData::class.java) ?: SessionData()
            }
            legacyNestedSnapshots(project, encodedBranch)?.let { dir ->
                val f = File(dir, "session.json")
                if (f.exists()) return gson.fromJson(f.readText(), SessionData::class.java) ?: SessionData()
            }
            gitSnapshotsDir(project, encodedBranch)?.let { dir ->
                val f = File(dir, "session.json")
                if (f.exists()) return gson.fromJson(f.readText(), SessionData::class.java) ?: SessionData()
            }
            SessionData()
        } catch (e: Exception) {
            BlamelyLogger.warn("Could not load session.json: ${e.message}")
            SessionData()
        }
    }

    /** Save all file blame for a specific branch (e.g. before switching branch). */
    fun saveAllToBranch(project: Project, branch: String, data: Map<String, List<LineBlame>>, blameMap: BlameMap? = null) {
        val snapshots = userSnapshotsDir(project, branch) ?: return
        if (!snapshots.exists()) snapshots.mkdirs()
        data.forEach { (filePath, entries) ->
            if (entries.isEmpty()) return@forEach
            try {
                val encoded = Platform.encodeFilePath(filePath) + ".blame.json"
                File(snapshots, encoded).writeText(serialize(entries))
            } catch (e: Exception) {
                BlamelyLogger.error("Failed to save blame for $filePath to branch $branch", e)
            }
        }
        blameMap?.let { saveSession(project, it, branch) }
    }

    private fun serialize(entries: List<LineBlame>): String {
        val sb = StringBuilder("[\n")
        entries.forEachIndexed { i, e ->
            if (i > 0) sb.append(",\n")
            sb.append("  {\n")
            sb.append("    \"line_number\": ${e.lineNumber},\n")
            sb.append("    \"author_type\": \"${e.authorType.name.lowercase()}\",\n")
            sb.append("    \"provider\": ${e.provider?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"},\n")
            sb.append("    \"timestamp\": \"${e.timestamp.replace("\"", "\\\"")}\",\n")
            sb.append("    \"commit_sha\": ${e.commitSha?.let { "\"$it\"" } ?: "null"},\n")
            sb.append("    \"model\": ${e.model?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"},\n")
            sb.append("    \"prompt\": ${e.prompt?.let { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\"" } ?: "null"},\n")
            sb.append("    \"interaction_type\": ${e.interactionType?.let { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\"" } ?: "null"},\n")
            sb.append("    \"ai_chars\": ${e.aiChars},\n")
            sb.append("    \"human_chars\": ${e.humanChars},\n")
            sb.append("    \"coding_type\": \"${e.codingType.name.lowercase()}\"\n")
            sb.append("  }")
        }
        sb.append("\n]")
        return sb.toString()
    }

    private fun parse(json: String): List<LineBlame> {
        return try {
            val el = com.google.gson.JsonParser.parseString(json)
            if (!el.isJsonArray) return emptyList()
            val arr = el.asJsonArray
            (0 until arr.size()).mapNotNull { i ->
                val e = arr.get(i)
                if (!e.isJsonObject) return@mapNotNull null
                val o = e.asJsonObject
                fun num(key: String): Int = run {
                    val p = o.get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return@run 0
                    try {
                        p.asInt
                    } catch (_: Exception) {
                        0
                    }
                }
                fun str(key: String): String? = o.get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString
                LineBlame(
                    lineNumber = num("line_number"),
                    authorType = when (str("author_type").orEmpty()) {
                        "ai" -> LineBlame.AuthorType.AI
                        else -> LineBlame.AuthorType.HUMAN
                    },
                    provider = str("provider"),
                    timestamp = str("timestamp") ?: "",
                    commitSha = str("commit_sha"),
                    model = str("model"),
                    prompt = str("prompt"),
                    interactionType = str("interaction_type"),
                    aiChars = num("ai_chars"),
                    humanChars = num("human_chars"),
                    codingType = when (str("coding_type").orEmpty()) {
                        "bulk_insert" -> LineBlame.CodingType.BULK_INSERT
                        "file_add" -> LineBlame.CodingType.TYPING
                        "file_move" -> LineBlame.CodingType.TYPING
                        else -> LineBlame.CodingType.TYPING
                    }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
