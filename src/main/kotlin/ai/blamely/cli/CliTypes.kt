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
)

data class DaemonStatus(
    val running: Boolean,
    val port: Int? = null,
)
