package ai.blamely.persistence

import ai.blamely.core.BlameMap
import ai.blamely.core.LineBlame
import ai.blamely.git.GitUtils
import ai.blamely.utils.BlamelyLogger
import ai.blamely.utils.Platform
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.project.Project
import java.io.File
import java.util.Locale

/**
 * Save/load per-file blame: primary location **`~/.blamely/repos/<id>/snapshots/<branch>/`**
 * (VS Code 1.1.0), with reads/removals also touching `.git/blamely/snapshots/<branch>/` and legacy
 * `~/.blamely/session/` layouts for migration.
 */
object BlameSerializer {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

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
            val key = Platform.normalizeBlamePersistenceKey(filePath, project.basePath)
            val encoded = Platform.encodeFilePath(key) + ".blame.json"
            val target = File(snapshots, encoded)
            target.writeText(serialize(entries))
            BlamelyLogger.info("Saved blame state to ${target.absolutePath}")
        } catch (e: Exception) {
            BlamelyLogger.error("Failed to save blame state for $filePath", e)
        }
    }

    /** Remove persisted sidecars for a file (SCM / local history rollback — do not leave empty `[ ]` snapshots). */
    fun removeSnapshot(project: Project, filePath: String) {
        try {
            val key = Platform.normalizeBlamePersistenceKey(filePath, project.basePath)
            val encodedBlame = Platform.encodeFilePath(key) + ".blame.json"
            val encodedPlain = Platform.encodeFilePath(key) + ".json"
            val dirs = listOfNotNull(
                userSnapshotsDir(project),
                legacyFlatSnapshots(project),
                legacyNestedSnapshots(project),
                gitSnapshotsDir(project)
            ).distinct()
            for (dir in dirs) {
                if (!dir.isDirectory) continue
                File(dir, encodedBlame).takeIf { it.isFile }?.delete()
                File(dir, encodedPlain).takeIf { it.isFile }?.delete()
            }
            BlamelyLogger.info("Removed persisted blame snapshot(s) for $filePath")
        } catch (e: Exception) {
            BlamelyLogger.warn("Could not remove blame snapshot for $filePath: ${e.message}")
        }
    }

    fun load(project: Project, filePath: String): List<LineBlame> {
        val key = Platform.normalizeBlamePersistenceKey(filePath, project.basePath)
        val encoded = Platform.encodeFilePath(key) + ".blame.json"
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

    /** Load *.blame.json from an absolute file (e.g. under closed/<sha>/snapshots/). */
    fun loadSnapshotFile(f: File): List<LineBlame> {
        if (!f.isFile) return emptyList()
        return try {
            parse(f.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Prefer repo-relative encoded name, then project-relative (multi-root parity with VS Code).
     */
    fun resolveArchivedSnapshotFile(closedDir: File?, repoRel: String, projectRel: String): File? {
        if (closedDir == null || !closedDir.isDirectory) return null
        val r = repoRel.replace('\\', '/')
        val p = projectRel.replace('\\', '/')
        val c1 = File(closedDir, Platform.encodeFilePath(r) + ".blame.json")
        if (c1.isFile) return c1
        val c2 = File(closedDir, Platform.encodeFilePath(p) + ".blame.json")
        return if (c2.isFile) c2 else null
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

    /** Delete all persisted blame snapshots for the current branch (primary + legacy dirs). */
    fun clearCurrentBranchSnapshots(project: Project) {
        try {
            val dirs = listOfNotNull(
                userSnapshotsDir(project),
                legacyFlatSnapshots(project),
                legacyNestedSnapshots(project),
                gitSnapshotsDir(project)
            ).distinct()
            for (snapshots in dirs) {
                if (!snapshots.isDirectory) continue
                snapshots.listFiles()?.filter { it.name.endsWith(".blame.json") }?.forEach { it.delete() }
            }
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
                val key = Platform.normalizeBlamePersistenceKey(filePath, project.basePath)
                val encoded = Platform.encodeFilePath(key) + ".blame.json"
                File(snapshots, encoded).writeText(serialize(entries))
            } catch (e: Exception) {
                BlamelyLogger.error("Failed to save blame for $filePath to branch $branch", e)
            }
        }
        blameMap?.let { saveSession(project, it, branch) }
    }

    private fun serialize(entries: List<LineBlame>): String {
        val arr = com.google.gson.JsonArray()
        for (e in entries) {
            arr.add(lineBlameToJsonObject(e))
        }
        return gson.toJson(arr)
    }

    private fun normalizeAiInteractionTypeForDisk(raw: String?): String {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return "completion"
        val lower = t.lowercase(Locale.ROOT)
        if (lower == "ai_cli_trace" || lower.startsWith("blamely-cli-")) return "cli"
        if (lower == "chat_panel" || lower == "panel") return "panel"
        if (lower == "chat_inline" || lower == "chat") return "chat"
        if (lower == "completion") return "completion"
        if (lower.contains("cli") || lower.contains("trace")) return "cli"
        if (lower.contains("panel") || (lower.contains("chat") && lower.contains("workbench"))) return "panel"
        if (lower.contains("inline") || lower.contains("ghost")) return "chat"
        return "completion"
    }

    /** Same coarse buckets as VS Code `blameJsonPersist.ts`: human → JsonNull; AI → completion | chat | panel | cli. */
    private fun interactionTypeForBlameJson(e: LineBlame): com.google.gson.JsonElement {
        if (e.authorType == LineBlame.AuthorType.HUMAN) {
            return com.google.gson.JsonNull.INSTANCE
        }
        return com.google.gson.JsonPrimitive(normalizeAiInteractionTypeForDisk(e.interactionType))
    }

    private fun lineBlameToJsonObject(e: LineBlame): com.google.gson.JsonObject {
        val o = com.google.gson.JsonObject()
        o.addProperty("lineNumber", e.lineNumber)
        o.addProperty("authorType", if (e.authorType == LineBlame.AuthorType.AI) "AI" else "HUMAN")
        o.addProperty("changeType", e.changeType.name)
        if (e.model != null && e.model!!.isNotBlank()) o.addProperty("model", e.model)
        o.addProperty("codingType", e.codingType.name)
        o.add("interactionType", interactionTypeForBlameJson(e))
        if (e.timestamp.isNotEmpty()) o.addProperty("timestamp", e.timestamp)
        if (e.commitSha != null && e.commitSha!!.isNotBlank()) o.addProperty("commitSha", e.commitSha)
        if (e.prompt != null && e.prompt!!.isNotBlank()) o.addProperty("prompt", e.prompt)
        if (e.ide != null && e.ide!!.isNotBlank()) o.addProperty("ide", e.ide)
        if (e.aiChars != 0) o.addProperty("aiChars", e.aiChars)
        if (e.humanChars != 0) o.addProperty("humanChars", e.humanChars)
        e.oldLineNumber?.let { o.addProperty("oldLineNumber", it) }
        return o
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
                fun prim(key: String) = o.get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

                fun num(vararg keys: String): Int {
                    for (k in keys) {
                        val p = prim(k) ?: continue
                        try {
                            return p.asInt
                        } catch (_: Exception) {
                            try {
                                return p.asString.toInt()
                            } catch (_: Exception) {
                                continue
                            }
                        }
                    }
                    return 0
                }

                fun str(vararg keys: String): String? {
                    for (k in keys) {
                        val p = prim(k) ?: continue
                        if (p.isString) return p.asString
                    }
                    return null
                }

                val authorRaw = str("authorType", "author_type").orEmpty()
                val authorType = when (authorRaw.uppercase()) {
                    "AI" -> LineBlame.AuthorType.AI
                    else -> LineBlame.AuthorType.HUMAN
                }
                var aiChars = num("aiChars", "ai_chars")
                var humanChars = num("humanChars", "human_chars")
                if (aiChars == 0 && humanChars == 0) {
                    if (authorType == LineBlame.AuthorType.AI) aiChars = 1 else humanChars = 1
                }

                val codingRaw = str("codingType", "coding_type").orEmpty()
                val codingType = when (codingRaw.uppercase()) {
                    "BULK_INSERT", "BULKINSERT" -> LineBlame.CodingType.BULK_INSERT
                    "bulk_insert" -> LineBlame.CodingType.BULK_INSERT
                    "FILE_ADD", "file_add", "FILE_MOVE", "file_move" -> LineBlame.CodingType.TYPING
                    else -> LineBlame.CodingType.TYPING
                }

                val changeRaw = str("changeType", "change_type").orEmpty()
                val changeType = when (changeRaw.uppercase()) {
                    "DELETE" -> LineBlame.ChangeType.DELETE
                    else -> LineBlame.ChangeType.ADD
                }

                val newLn = prim("newLineNumber") ?: prim("new_line_number")
                val oldLn = prim("oldLineNumber") ?: prim("old_line_number")

                LineBlame(
                    lineNumber = num("lineNumber", "line_number"),
                    authorType = authorType,
                    provider = null,
                    timestamp = str("timestamp") ?: "",
                    commitSha = str("commitSha", "commit_sha"),
                    model = str("model"),
                    prompt = str("prompt"),
                    interactionType = str("interactionType", "interaction_type"),
                    aiChars = aiChars,
                    humanChars = humanChars,
                    changeType = changeType,
                    newLineNumber = newLn?.takeIf { it.isNumber }?.asInt,
                    oldLineNumber = oldLn?.takeIf { it.isNumber }?.asInt,
                    codingType = codingType,
                    ide = str("ide", "ide_label")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
