package ai.blamely.cli

data class CliEditRow(
    val id: Long,
    val ts: Long,
    val filePath: String,
    val tool: String,
    val model: String?,
    val genType: String,
    val startLine: Int,
    val endLine: Int,
    // Per-line content hash when the edit recorded line content (chat applies);
    // null for range-only edits. Used to attribute a line to AI only when its
    // current content still matches what the AI applied.
    val contentSha: String? = null,
)

data class DaemonStatus(
    val running: Boolean,
    val port: Int? = null,
)
