package ai.blamely.cli

import java.sql.DriverManager

object CliSqliteReader {
    private val AI_TOOLS = setOf("claude", "cursor", "codex", "copilot", "gemini")

    fun isAiTool(tool: String): Boolean = tool.lowercase() in AI_TOOLS

    fun loadEditsForRepo(repoId: String): List<CliEditRow> {
        val db = CliPaths.dbFile()
        if (!db.isFile) return emptyList()
        val url = "jdbc:sqlite:${db.absolutePath}"
        return try {
            DriverManager.getConnection(url).use { conn ->
                conn.prepareStatement(
                    """
                    SELECT e.id, e.ts, e.file_path, e.tool, e.model, e.gen_type,
                           el.start_line, el.end_line
                    FROM edits e
                    JOIN edit_lines el ON el.edit_id = e.id
                    WHERE e.repo_path = ?
                    ORDER BY e.ts DESC, e.id DESC
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, repoId)
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
