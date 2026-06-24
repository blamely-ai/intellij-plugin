// Shared shape + converter for Attribution v2 working logs (`blamely authorship`
// output), consumed by CliDataService to fill the BlameMap (which BlameDecorations
// paints). Mirrors the VS Code workingLogBlame.ts.
package ai.blamely.authorship

import ai.blamely.core.LineBlame
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.util.SystemInfo
import java.io.File

data class WorkingLogJson(val file: String? = null, val lines: List<WlLine>? = null)

data class WlLine(
    val start: Int = 0,
    val end: Int = 0,
    val author: String = "human",
    val tool: String? = null,
    val model: String? = null,
    @SerializedName("gen_type") val genType: String? = null,
)

/** Expands a working log's per-range authors into the per-line LineBlame the gutter
 *  renderer consumes (one entry per line; AI → AI icon, else Human). */
fun workingLogToLineBlame(wl: WorkingLogJson): List<LineBlame> {
    val out = ArrayList<LineBlame>()
    for (r in wl.lines ?: emptyList()) {
        val ai = r.author == "ai"
        var ln = r.start
        while (ln <= r.end) {
            out.add(
                LineBlame(
                    lineNumber = ln,
                    authorType = if (ai) LineBlame.AuthorType.AI else LineBlame.AuthorType.HUMAN,
                    timestamp = "",
                    provider = if (ai) r.tool else null,
                    model = if (ai) r.model else null,
                    interactionType = if (ai) r.genType else null,
                    aiChars = if (ai) 1 else 0,
                    humanChars = if (ai) 0 else 1,
                    changeType = LineBlame.ChangeType.ADD,
                    codingType = LineBlame.CodingType.TYPING,
                ),
            )
            ln++
        }
    }
    return out
}

/** Path to the installed blamely binary (honors BLAMELY_HOME), used to run the
 *  `authorship` command for the v2 gutter source. */
fun blamelyBinaryPath(): String {
    val home = System.getenv("BLAMELY_HOME")?.takeIf { it.isNotBlank() }
        ?: (System.getProperty("user.home") + File.separator + ".blamely")
    val name = if (SystemInfo.isWindows) "blamely.exe" else "blamely"
    return home + File.separator + "bin" + File.separator + name
}
