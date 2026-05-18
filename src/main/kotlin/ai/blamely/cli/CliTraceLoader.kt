package ai.blamely.cli

import ai.blamely.persistence.BlamelyUserRepoPaths
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File

data class CliFileEntry(
    val path: String = "",
    val classification: String = "",
    val confidence: String = "",
    val reasons: List<String> = emptyList()
)

data class CliGit(
    val branch: String = "",
    @SerializedName("head_at_start") val headAtStart: String = "",
    @SerializedName("head_after_trace") val headAfterTrace: String? = null
)

data class CliTracedCommand(
    val argv: List<String> = emptyList(),
    @SerializedName("exit_code") val exitCode: Int = 0
)

data class CliTraceSession(
    @SerializedName("schema_version") val schemaVersion: Int = 0,
    @SerializedName("trace_id") val traceId: String = "",
    val scope: String = "",
    @SerializedName("started_at") val startedAt: String = "",
    @SerializedName("ended_at") val endedAt: String = "",
    @SerializedName("repo_root") val repoRoot: String = "",
    val git: CliGit? = null,
    @SerializedName("traced_command") val tracedCommand: CliTracedCommand? = null,
    @SerializedName("report_model") val reportModel: String? = null,
    val files: List<CliFileEntry> = emptyList(),
    @SerializedName("watch_touched") val watchTouched: List<String>? = null
)

/**
 * Loads blamely-cli sessions from legacy `cli-traces/<uuid>/session.json` only.
 * `branches/<branch>/trace/session.json` is not read.
 */
object CliTraceLoader {
    private val gson = Gson()

    private fun isCliTraceScope(scope: String): Boolean =
        scope == "ai_cli_trace" || scope.startsWith("blamely-cli-")

    fun loadAll(repoRoot: File, layoutRoot: File = BlamelyUserRepoPaths.blamelyUserLayoutRoot()): List<CliTraceSession> {
        val out = mutableListOf<CliTraceSession>()
        val seenIds = mutableSetOf<String>()
        val legacy = BlamelyUserRepoPaths.cliTraceParentDir(repoRoot, layoutRoot)
        if (legacy.isDirectory) {
            for (d in legacy.listFiles().orEmpty()) {
                if (!d.isDirectory) continue
                readSessionFile(File(d, "session.json"))?.let { doc ->
                    if (seenIds.add(doc.traceId)) {
                        out.add(doc)
                    }
                }
            }
        }
        return out.sortedByDescending { it.endedAt }
    }

    private fun readSessionFile(f: File): CliTraceSession? {
        if (!f.isFile) return null
        return try {
            val doc = gson.fromJson(f.readText(), CliTraceSession::class.java) ?: return null
            if (!isCliTraceScope(doc.scope) || doc.traceId.isBlank()) return null
            doc
        } catch (_: Exception) {
            null
        }
    }
}
