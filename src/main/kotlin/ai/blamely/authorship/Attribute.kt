// Attribution v2 engine — Kotlin port of internal/authorship (Go) in blamely-cli.
// MUST stay behavior-compatible with the Go and TypeScript implementations: all
// three run the shared golden vectors (src/test/resources/golden_vectors.json,
// synced from blamely-cli's canonical copy), so any drift fails AttributionGoldenTest.
// See docs/attribution-v2-design.md §6. Cross-platform: pure string logic.
package ai.blamely.authorship

const val WORKING_LOG_SCHEMA = "blamely/working-log/1"

enum class AuthorType(val wire: String) {
    HUMAN("human"),
    AI("ai");

    companion object {
        fun fromWire(s: String): AuthorType = if (s == "ai") AI else HUMAN
    }
}

data class Author(
    val type: AuthorType,
    val tool: String = "",
    val model: String = "",
    val genType: String = "",
    val session: String = "",
)

// overrode records the author a changed line replaced (audit marker); null when
// the line was not an override.
data class LineAttribution(val start: Int, val end: Int, val author: Author, val overrode: Author? = null)

data class WorkingLog(
    val schema: String = WORKING_LOG_SCHEMA,
    val file: String = "",
    val baseSha: String = "",
    val lines: List<LineAttribution> = emptyList(),
)

fun humanAuthor(): Author = Author(AuthorType.HUMAN, genType = "human")

/**
 * attribute is THE engine: unchanged (LCS-matched) lines keep their prior author;
 * added/changed lines become [author]; uncovered lines default to Human. No
 * content-hash guessing — duplicate/moved identical lines resolve by diff position.
 */
fun attribute(prior: WorkingLog?, baseline: String, newContent: String, author: Author): WorkingLog {
    val oldLines = splitLines(baseline)
    val newLines = splitLines(newContent)
    val matched = alignLines(oldLines, newLines)

    // movedFrom[i] = old index a new line was MOVED from (relocated identical
    // content), or -1.
    val movedFrom = detectMoves(oldLines, newLines, matched)

    val perLine = ArrayList<Author>(newLines.size)
    for (i in newLines.indices) {
        val j = matched[i]
        perLine.add(
            when {
                j >= 0 -> priorAuthorOr(prior, j + 1)
                movedFrom[i] >= 0 -> priorAuthorOr(prior, movedFrom[i] + 1)
                else -> author
            }
        )
    }
    // overrode[i] = the author a CHANGED line replaced, when its type differs from
    // the new author (audit marker; does not change who owns the line now).
    val overrode = detectOverrode(prior, matched, movedFrom, oldLines.size, author)
    return WorkingLog(
        schema = WORKING_LOG_SCHEMA,
        file = prior?.file ?: "",
        baseSha = prior?.baseSha ?: "",
        lines = coalesce(perLine, overrode),
    )
}

/** detectMoves pairs each unmatched NEW line with an unmatched OLD line of identical
 *  (whitespace-normalized) content — FIFO by content. Identical to the Go and TS
 *  ports. A new line with no surviving deleted twin is a genuine add (-1). */
private fun detectMoves(oldLines: List<String>, newLines: List<String>, matched: IntArray): IntArray {
    val moved = IntArray(newLines.size) { -1 }
    val oldMatched = BooleanArray(oldLines.size)
    for (j in matched) if (j >= 0) oldMatched[j] = true
    val oldN = oldLines.map { normalizeLineForMatch(it) }
    val newN = newLines.map { normalizeLineForMatch(it) }
    val queues = HashMap<String, ArrayDeque<Int>>()
    for (oi in oldLines.indices) {
        if (!oldMatched[oi]) queues.getOrPut(oldN[oi]) { ArrayDeque() }.addLast(oi)
    }
    for (ni in newLines.indices) {
        if (matched[ni] >= 0) continue
        val q = queues[newN[ni]]
        if (q != null && q.isNotEmpty()) moved[ni] = q.removeFirst()
    }
    return moved
}

/** detectOverrode finds replace pairs and records the replaced author when its type
 *  differs from the new author. Walks the LCS gap by gap and pairs, positionally, the
 *  NEW lines that are neither matched nor moved against the OLD lines not consumed by
 *  a move — identical to the Go and TS ports. Moves never override. */
private fun detectOverrode(prior: WorkingLog?, matched: IntArray, movedFrom: IntArray, nOld: Int, author: Author): Array<Author?> {
    val m = matched.size
    val overrode = arrayOfNulls<Author>(m)
    val consumedOld = BooleanArray(nOld)
    for (mf in movedFrom) if (mf >= 0) consumedOld[mf] = true
    var oldCursor = 0
    var i = 0
    while (i < m) {
        if (matched[i] >= 0) {
            oldCursor = matched[i] + 1
            i++
            continue
        }
        var gapNewEnd = i
        while (gapNewEnd < m && matched[gapNewEnd] < 0) gapNewEnd++
        val gapOldEnd = if (gapNewEnd < m) matched[gapNewEnd] else nOld
        val newAvail = ArrayList<Int>()
        val oldAvail = ArrayList<Int>()
        for (ni in i until gapNewEnd) if (movedFrom[ni] < 0) newAvail.add(ni)
        for (oi in oldCursor until gapOldEnd) if (!consumedOld[oi]) oldAvail.add(oi)
        var k = 0
        while (k < newAvail.size && k < oldAvail.size) {
            val replaced = priorAuthorOr(prior, oldAvail[k] + 1)
            if (replaced.type != author.type) overrode[newAvail[k]] = replaced
            k++
        }
        oldCursor = gapOldEnd
        i = gapNewEnd
    }
    return overrode
}

private fun priorAuthorOr(prior: WorkingLog?, line: Int): Author {
    if (prior != null) {
        for (r in prior.lines) {
            if (line >= r.start && line <= r.end) return r.author
        }
    }
    return humanAuthor()
}

/** Drops the trailing empty element from a final newline and strips a trailing CR
 *  so CRLF (Windows) and LF compare equal — matches the Go and TS ports. */
private fun splitLines(s: String): List<String> {
    if (s.isEmpty()) return emptyList()
    val parts = s.split("\n").toMutableList()
    if (parts.isNotEmpty() && parts.last() == "") parts.removeAt(parts.size - 1)
    return parts.map { it.removeSuffix("\r") }
}

/** For each NEW line, the OLD line index it is unchanged from (LCS match) or -1.
 *  Standard LCS DP + backtrack; identical to the Go/TS implementations. */
// normalizeLineForMatch reduces a line to its whitespace-insensitive form by
// REMOVING all whitespace (git diff -w semantics) — indentation, trailing, and
// operator spacing (`x=1` ↔ `x = 1`) all read as reflow and keep the prior author.
// MUST match the Go and TypeScript ports exactly (the golden vectors enforce it).
private fun normalizeLineForMatch(s: String): String = s.filterNot { it.isWhitespace() }

// alignLines compares lines WHITESPACE-NORMALIZED (Phase 4 reflow): a line that
// changed only in indentation / trailing or collapsed whitespace counts as
// unchanged and keeps its prior author. A genuine content change still mismatches.
private fun alignLines(oldLines: List<String>, newLines: List<String>): IntArray {
    val n = oldLines.size
    val m = newLines.size
    val matched = IntArray(m) { -1 }
    if (n == 0 || m == 0) return matched

    val oldN = oldLines.map { normalizeLineForMatch(it) }
    val newN = newLines.map { normalizeLineForMatch(it) }
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = when {
                oldN[i] == newN[j] -> dp[i + 1][j + 1] + 1
                dp[i + 1][j] >= dp[i][j + 1] -> dp[i + 1][j]
                else -> dp[i][j + 1]
            }
        }
    }
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            oldN[i] == newN[j] -> {
                matched[j] = i; i++; j++
            }
            dp[i + 1][j] >= dp[i][j + 1] -> i++
            else -> j++
        }
    }
    return matched
}

private fun coalesce(perLine: List<Author>, overrode: Array<Author?>): List<LineAttribution> {
    val out = ArrayList<LineAttribution>()
    for (idx in perLine.indices) {
        val ln = idx + 1
        val a = perLine[idx]
        val ov = overrode[idx]
        val last = out.lastOrNull()
        if (last != null && last.end == ln - 1 && last.author == a && last.overrode == ov) {
            out[out.size - 1] = last.copy(end = ln)
            continue
        }
        out.add(LineAttribution(ln, ln, a, ov))
    }
    return out
}
