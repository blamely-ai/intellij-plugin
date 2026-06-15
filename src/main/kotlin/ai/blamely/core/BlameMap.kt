package ai.blamely.core

/** Read-only per-file line blame populated from oobeya-cli SQLite. */
class BlameMap {
    private val map = mutableMapOf<String, MutableList<LineBlame>>()

    private fun normPath(path: String): String = path.replace('\\', '/')

    fun getBlame(filePath: String): List<LineBlame> =
        map[normPath(filePath)]?.toList() ?: emptyList()

    fun getTrackedFiles(): List<String> = map.keys.toList()

    fun setFileBlame(filePath: String, entries: List<LineBlame>) {
        map[normPath(filePath)] = entries.toMutableList()
    }

    /**
     * Atomically replace the whole map in ONE operation. Unlike clear()+setFileBlame
     * in a loop, there is no intermediate empty state, so the gutter never momentarily
     * shows nothing (no AI→blank→repopulate flicker) during a refresh.
     */
    fun replaceAll(newMap: Map<String, List<LineBlame>>) {
        map.clear()
        for ((path, entries) in newMap) {
            map[normPath(path)] = entries.toMutableList()
        }
    }

    fun getSummary(): Summary {
        val acc = MutableSummary()
        for ((_, entries) in map) accumulate(acc, entries) { false }
        return acc.toSummary()
    }

    /**
     * AI/Human summary for a SINGLE file, matching what the gutter shows. Uses
     * getBlame's path resolution and the same per-line dedup as getSummary, and
     * (via [isBlankLine]) skips blank lines so the count equals the gutter icons
     * in the active editor — the gutter draws no icon on a blank line, but a
     * blank still carries a coerced char count, so a workspace-wide getSummary
     * over-counts. Callers supply isBlankLine from the live document; the default
     * keeps every line (used where no document is available).
     */
    fun getSummaryForFile(filePath: String, isBlankLine: (Int) -> Boolean = { false }): Summary {
        val acc = MutableSummary()
        accumulate(acc, getBlame(filePath), isBlankLine)
        return acc.toSummary()
    }

    /** Fold one file's entries into [acc]: dedup by line (best entry wins), drop
     *  DELETE rows and blank lines, then tally AI vs Human. Shared by getSummary
     *  and getSummaryForFile so the workspace total and per-file count agree. */
    private fun accumulate(acc: MutableSummary, entries: List<LineBlame>, isBlankLine: (Int) -> Boolean) {
        val byLine = linkedMapOf<Int, LineBlame>()
        for (e in entries) {
            if (e.changeType == LineBlame.ChangeType.DELETE) continue
            byLine[e.lineNumber] = LineBlame.betterLineEntry(byLine[e.lineNumber], e)
        }
        for ((line, e) in byLine) {
            if (e.aiChars + e.humanChars == 0) continue
            if (isBlankLine(line)) continue
            acc.aiChars += e.aiChars
            acc.humanChars += e.humanChars
            if (e.effectiveAuthorType() == LineBlame.AuthorType.AI) acc.aiLines++ else acc.humanLines++
        }
    }

    private class MutableSummary {
        var aiChars = 0
        var humanChars = 0
        var aiLines = 0
        var humanLines = 0
        fun toSummary() = Summary(aiChars, humanChars, aiLines, humanLines, aiLines + humanLines)
    }

    fun clear() {
        map.clear()
    }

    data class Summary(
        val aiChars: Int,
        val humanChars: Int,
        val aiLines: Int,
        val humanLines: Int,
        val totalLines: Int,
    )
}
