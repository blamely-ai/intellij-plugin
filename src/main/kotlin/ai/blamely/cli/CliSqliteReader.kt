package ai.blamely.cli

import java.io.File
import java.sql.DriverManager

object CliSqliteReader {
    private val AI_TOOLS = setOf("claude", "cursor", "codex", "copilot", "gemini")

    fun isAiTool(tool: String): Boolean = tool.lowercase() in AI_TOOLS

    fun loadEditsForRepo(repoRoot: String, sinceTs: Long = 0): List<CliEditRow> {
        val repoIds = linkedSetOf<String>()
        CliRepoId.get(repoRoot)?.let { repoIds.add(it) }
        try {
            repoIds.add(File(repoRoot).canonicalPath)
        } catch (_: Exception) {
        }
        repoIds.add(repoRoot.trimEnd('/', '\\'))
        for (repoId in repoIds) {
            val rows = loadEditsForRepoId(repoId, sinceTs)
            if (rows.isNotEmpty()) return rows
        }
        return emptyList()
    }

    private fun loadEditsForRepoId(repoId: String, sinceTs: Long = 0): List<CliEditRow> {
        val db = CliPaths.dbFile()
        if (!db.isFile) return emptyList()
        val url = "jdbc:sqlite:${db.absolutePath}"
        return try {
            DriverManager.getConnection(url).use { conn ->
                conn.prepareStatement(
                    """
                    SELECT e.id, e.ts, e.file_path, e.tool, e.model, e.gen_type,
                           el.start_line, el.end_line, el.content_sha
                    FROM edits e
                    JOIN edit_lines el ON el.edit_id = e.id
                    WHERE e.repo_path = ? AND e.ts >= ?
                    ORDER BY e.ts DESC, e.id DESC
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, repoId)
                    ps.setLong(2, sinceTs)
                    ps.executeQuery().use { rs ->
                        val out = mutableListOf<CliEditRow>()
                        while (rs.next()) {
                            val file = rs.getString("file_path") ?: continue
                            val start = rs.getInt("start_line")
                            val end = rs.getInt("end_line")
                            if (file.isBlank() || start <= 0 || end < start) continue
                            out.add(
                                CliEditRow(
                                    id = rs.getLong("id"),
                                    ts = rs.getLong("ts"),
                                    filePath = file.replace('\\', '/'),
                                    tool = rs.getString("tool") ?: "human",
                                    model = rs.getString("model"),
                                    genType = rs.getString("gen_type") ?: "unknown",
                                    startLine = start,
                                    endLine = end,
                                    contentSha = rs.getString("content_sha")?.takeIf { it.isNotBlank() },
                                )
                            )
                        }
                        out
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
