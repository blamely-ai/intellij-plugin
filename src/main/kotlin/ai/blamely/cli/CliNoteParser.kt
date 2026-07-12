package ai.blamely.cli

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class CliNote(
    val schema: Int = 0,
    val commit: String = "",
    val branch: String = "",
    val message: String = "",
    @SerializedName("coding_time_nanos") val codingTimeNanos: Long = 0,
    @SerializedName("generated_by") val generatedBy: String? = null,
    val totals: CliNoteTotals = CliNoteTotals(),
    @SerializedName("by_tool") val byTool: Map<String, CliNoteTool>? = null,
    @SerializedName("by_gen_type") val byGenType: CliNoteGenType? = null,
    val files: List<CliNoteFile>? = null,
)

data class CliNoteTotals(
    @SerializedName(value = "ai_added_lines", alternate = ["ai_lines"]) val aiLines: Int = 0,
    @SerializedName(value = "human_added_lines", alternate = ["human_lines"]) val humanLines: Int = 0,
    @SerializedName("deleted_lines") val deletedLines: Int = 0,
    @SerializedName("ai_deleted_lines") val aiDeletedLines: Int = 0,
    @SerializedName("human_deleted_lines") val humanDeletedLines: Int = 0,
    val files: Int = 0,
    val models: Map<String, Int>? = null,
)

data class CliNoteTool(val lines: Int = 0, val model: String? = null)

data class CliNoteGenType(
    val chat: Int = 0,
    val cli: Int = 0,
    val completion: Int = 0,
    val unknown: Int = 0,
)

data class CliNoteFile(
    val path: String = "",
    val type: String? = null,
    @SerializedName(value = "added_lines", alternate = ["added"]) val added: Int = 0,
    @SerializedName(value = "deleted_lines", alternate = ["deleted"]) val deleted: Int = 0,
    // Per-file AI/Human split of added_lines/deleted_lines (same keys as totals); 0 when omitted.
    @SerializedName("ai_added_lines") val aiAddedLines: Int = 0,
    @SerializedName("human_added_lines") val humanAddedLines: Int = 0,
    @SerializedName("ai_deleted_lines") val aiDeletedLines: Int = 0,
    @SerializedName("human_deleted_lines") val humanDeletedLines: Int = 0,
    val lines: List<CliNoteLine>? = null,
)

data class CliNoteLine(
    val line: Int = 0,
    val type: String = "",
    @SerializedName("author_type") val authorType: String? = null,
    val tool: String? = null,
    val model: String? = null,
    @SerializedName("gen_type") val genType: String? = null,
)

object CliNoteParser {
    private val gson = Gson()

    fun parse(raw: String): CliNote? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            val note = gson.fromJson(trimmed, CliNote::class.java)
            if ((note.schema != 1 && note.schema != 2) || note.commit.isBlank()) null else note
        } catch (_: Exception) {
            null
        }
    }

    fun genTypes(note: CliNote): List<String> {
        val gt = note.byGenType ?: return emptyList()
        return buildList {
            if (gt.chat > 0) add("chat")
            if (gt.cli > 0) add("cli")
            if (gt.completion > 0) add("completion")
            if (gt.unknown > 0) add("unknown")
        }
    }

    fun models(note: CliNote): List<String> {
        val fromTotals = note.totals.models?.keys
            ?.filter { it.isNotBlank() && it != "unknown" }
            ?.toList()
        if (!fromTotals.isNullOrEmpty()) return fromTotals
        return note.byTool?.keys?.filter { it.isNotBlank() && it != "unknown" }?.sorted() ?: emptyList()
    }

    fun codingTimeMs(note: CliNote): Long = note.codingTimeNanos / 1_000_000
}
