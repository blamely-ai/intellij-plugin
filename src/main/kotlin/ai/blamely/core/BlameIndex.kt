package ai.blamely.core

/**
 * Re-indexes line numbers in blame entries when lines are inserted or deleted.
 * Pure function: returns a new list (mirrors VS Code BlameIndex.ts).
 * Use this when you need immutable reindex; BlameMap.reindex() mutates in place.
 */
object BlameIndex {

    /**
     * @param entries existing blame entries for a file
     * @param changeStartLine 1-based line where the change starts
     * @param linesInserted number of lines inserted
     * @param linesDeleted number of lines deleted
     * @return new list with line numbers updated (entries in deleted range removed)
     */
    fun reindex(
        entries: List<LineBlame>,
        changeStartLine: Int,
        linesInserted: Int,
        linesDeleted: Int
    ): List<LineBlame> {
        val netChange = linesInserted - linesDeleted
        if (netChange == 0 && linesInserted == 0) return entries

        return entries.mapNotNull { entry ->
            when {
                entry.lineNumber < changeStartLine -> entry
                entry.lineNumber >= changeStartLine && entry.lineNumber < changeStartLine + linesDeleted -> null
                else -> entry.copy(lineNumber = entry.lineNumber + netChange)
            }
        }.sortedBy { it.lineNumber }
    }
}
