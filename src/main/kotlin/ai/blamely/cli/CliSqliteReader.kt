package ai.blamely.cli

import ai.blamely.utils.BlamelyLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Read-only SQLite access via the system `sqlite3` CLI (same approach as the VS Code plugin).
 *
 * We intentionally avoid sqlite-jdbc: it bundles SLF4J and triggers
 * `LinkageError: loader constraint violation` inside IntelliJ's PluginClassLoader.
 */
object CliSqliteReader {
    private val AI_TOOLS = setOf("claude", "cursor", "codex", "copilot", "gemini")
    private val gson = Gson()
    private val gsonRowType = object : TypeToken<List<Map<String, Any?>>>() {}.type

    private val SQLITE_BINS = listOf(
        "sqlite3",
        "/usr/bin/sqlite3",
        "/opt/homebrew/bin/sqlite3",
    )

    private enum class LoadMode { SESSION, BRANCH, LEGACY }

    fun isAiTool(tool: String): Boolean = tool.lowercase() in AI_TOOLS

    /**
     * Loads edits for the current work session on [repoRoot], with fallbacks for
     * IDE reopen: HEAD-scoped session → branch-scoped → legacy NULL rows.
     *
     * Returns null only when sqlite3 could not read the DB. An empty list means
     * the query succeeded but matched no rows.
     */
    fun loadEditsForRepo(repoRoot: String, branch: String?, workBaseHead: String?): List<CliEditRow>? {
        val head = workBaseHead?.trim().orEmpty()
        val repoIds = repoIdCandidates(repoRoot)
        var anySuccessfulRead = false

        for (mode in LoadMode.entries) {
            logSessionIdsForMode(repoIds, branch, head, mode)
            when (val result = queryEdits(repoIds, branch, head, mode)) {
                null -> { /* try next mode */ }
                else -> {
                    anySuccessfulRead = true
                    if (result.isNotEmpty()) {
                        BlamelyLogger.debug(
                            "sqlite: loaded ${result.size} edit lines mode=$mode " +
                                "repoIds=${repoIds.joinToString()} branch=$branch head=${head.take(12)}"
                        )
                        return result
                    }
                }
            }
        }

        if (anySuccessfulRead) {
            BlamelyLogger.debug(
                "sqlite: no edits for repoIds=${repoIds.joinToString()} branch=$branch head=${head.take(12)}"
            )
            return emptyList()
        }

        BlamelyLogger.debug(
            "sqlite: read failed (sqlite3 missing or DB locked) repoIds=${repoIds.joinToString()} " +
                "branch=$branch head=${head.take(12)}"
        )
        return null
    }

    private fun logSessionIdsForMode(
        repoIds: Collection<String>,
        branch: String?,
        head: String,
        mode: LoadMode,
    ) {
        if (!BlamelyLogger.isDebugEnabled() || branch == null) return
        val db = CliPaths.dbFile()
        if (!db.isFile) return
        val repoIn = repoPathInList(repoIds)
        val sql = when (mode) {
            LoadMode.SESSION ->
                "SELECT id, base_sha FROM sessions WHERE branch = '${esc(branch)}' " +
                    "AND repo_path IN ($repoIn) ORDER BY base_sha DESC LIMIT 8"
            LoadMode.BRANCH ->
                "SELECT id, base_sha FROM sessions WHERE branch = '${esc(branch)}' " +
                    "AND repo_path IN ($repoIn) LIMIT 8"
            LoadMode.LEGACY -> return
        }
        val rows = runSqliteJsonRaw(db, sql) ?: return
        val ids = rows.mapNotNull { r ->
            val id = r["id"]?.toString() ?: return@mapNotNull null
            val base = r["base_sha"]?.toString().orEmpty()
            "$id@${base.take(12)}"
        }
        BlamelyLogger.debug(
            "sqlite: sessions mode=$mode branch=$branch head=${head.take(12)} " +
                "candidates=$ids matchHead=${ids.any { it.contains(head.take(12)) }}"
        )
    }

    private fun repoIdCandidates(repoRoot: String): LinkedHashSet<String> {
        val repoIds = linkedSetOf<String>()
        CliRepoId.get(repoRoot)?.let { repoIds.add(it) }
        try {
            repoIds.add(File(repoRoot).canonicalPath)
        } catch (_: Exception) {
        }
        repoIds.add(repoRoot.trimEnd('/', '\\'))
        return repoIds
    }

    private fun queryEdits(
        repoIds: Collection<String>,
        branch: String?,
        workBaseHead: String,
        mode: LoadMode,
    ): List<CliEditRow>? {
        val db = CliPaths.dbFile()
        if (!db.isFile) return emptyList()
        val where = buildWhereSql(repoIds, branch, workBaseHead, mode)
        // Do not prefix PRAGMA: sqlite3 -json emits one JSON blob per statement; Gson
        // would only parse the first (pragma) row and return zero edits.
        val sql = """
            SELECT e.id AS id, e.ts AS ts, e.file_path AS file_path, e.tool AS tool,
                   e.model AS model, e.gen_type AS gen_type,
                   el.start_line AS start_line, el.end_line AS end_line, el.content_sha AS content_sha
            FROM edits e
            JOIN edit_lines el ON el.edit_id = e.id
            WHERE $where
            ORDER BY e.ts DESC, e.id DESC
        """.trimIndent()

        repeat(3) { attempt ->
            val rows = runSqliteJsonRaw(db, sql)
            if (rows != null) {
                return rows.mapNotNull { row ->
                    mapRow(
                        id = (row["id"] as? Number)?.toLong() ?: return@mapNotNull null,
                        ts = (row["ts"] as? Number)?.toLong() ?: return@mapNotNull null,
                        file = row["file_path"]?.toString(),
                        tool = row["tool"]?.toString(),
                        model = row["model"]?.toString(),
                        genType = row["gen_type"]?.toString(),
                        start = (row["start_line"] as? Number)?.toInt() ?: return@mapNotNull null,
                        end = (row["end_line"] as? Number)?.toInt() ?: return@mapNotNull null,
                        contentSha = row["content_sha"]?.toString(),
                    )
                }
            }
            if (attempt < 2) Thread.sleep(80L * (attempt + 1))
        }
        return null
    }

    private fun runSqliteJsonRaw(db: File, sql: String): List<Map<String, Any?>>? {
        val body = sql.trim()
        for (bin in SQLITE_BINS) {
            if (bin != "sqlite3" && !File(bin).canExecute()) continue
            try {
                val proc = ProcessBuilder(bin, "-json", db.absolutePath, body)
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.bufferedReader().readText()
                val code = proc.waitFor()
                if (code != 0) {
                    BlamelyLogger.debug("sqlite: $bin exit $code: ${out.take(200)}")
                    continue
                }
                val trimmed = out.trim()
                if (trimmed.isEmpty()) return emptyList()
                @Suppress("UNCHECKED_CAST")
                return gson.fromJson(trimmed, gsonRowType) as List<Map<String, Any?>>
            } catch (e: Exception) {
                BlamelyLogger.debug("sqlite: $bin failed: ${e.message}")
            }
        }
        return null
    }

    private fun mapRow(
        id: Long,
        ts: Long,
        file: String?,
        tool: String?,
        model: String?,
        genType: String?,
        start: Int,
        end: Int,
        contentSha: String?,
    ): CliEditRow? {
        val path = file?.replace('\\', '/')?.trim().orEmpty()
        if (path.isBlank() || start <= 0 || end < start) return null
        return CliEditRow(
            id = id,
            ts = ts,
            filePath = path,
            tool = tool ?: "human",
            model = model,
            genType = genType ?: "unknown",
            startLine = start,
            endLine = end,
            contentSha = contentSha?.takeIf { it.isNotBlank() },
        )
    }

    private fun esc(s: String): String = s.replace("'", "''")

    private fun repoPathInList(repoPaths: Collection<String>): String =
        repoPaths.distinct().filter { it.isNotBlank() }.joinToString(",") { "'${esc(it)}'" }

    private fun buildWhereSql(
        repoPaths: Collection<String>,
        branch: String?,
        workBaseHead: String,
        mode: LoadMode,
    ): String {
        val repoIn = repoPathInList(repoPaths)
        val branchKey = esc(branch ?: "")
        val head = esc(workBaseHead)

        return when (mode) {
            LoadMode.LEGACY -> {
                if (branch != null) {
                    "e.repo_path IN ($repoIn) AND (e.session_id IS NULL AND (e.branch = '$branchKey' OR e.branch IS NULL))"
                } else {
                    "e.repo_path IN ($repoIn) AND e.session_id IS NULL"
                }
            }
            LoadMode.BRANCH -> {
                if (branch != null) {
                    """
                    e.repo_path IN ($repoIn) AND (
                      e.branch = '$branchKey'
                      OR e.session_id IN (
                        SELECT id FROM sessions WHERE branch = '$branchKey' AND repo_path IN ($repoIn)
                      )
                    )
                    """.trimIndent()
                } else {
                    "e.repo_path IN ($repoIn) AND (e.branch IS NULL OR e.session_id IS NULL)"
                }
            }
            LoadMode.SESSION -> {
                val legacy =
                    if (branch != null) {
                        "(e.session_id IS NULL AND (e.branch = '$branchKey' OR e.branch IS NULL))"
                    } else {
                        "(e.session_id IS NULL AND e.branch IS NULL)"
                    }
                if (branch != null) {
                    """
                    e.repo_path IN ($repoIn) AND (
                      e.session_id IN (
                        SELECT id FROM sessions
                        WHERE branch = '$branchKey' AND base_sha = '$head' AND repo_path IN ($repoIn)
                      )
                      OR $legacy
                    )
                    """.trimIndent()
                } else {
                    "e.repo_path IN ($repoIn) AND $legacy"
                }
            }
        }
    }
}
