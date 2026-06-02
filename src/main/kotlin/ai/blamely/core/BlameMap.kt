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
        var aiChars = 0
        var humanChars = 0
        var aiLines = 0
        var humanLines = 0
        for ((_, entries) in map) {
            val byLine = linkedMapOf<Int, LineBlame>()
            for (e in entries) {
                if (e.changeType == LineBlame.ChangeType.DELETE) continue
                byLine[e.lineNumber] = LineBlame.betterLineEntry(byLine[e.lineNumber], e)
            }
            for (e in byLine.values) {
                if (e.aiChars + e.humanChars == 0) continue
                aiChars += e.aiChars
                humanChars += e.humanChars
                if (e.effectiveAuthorType() == LineBlame.AuthorType.AI) aiLines++ else humanLines++
            }
        }
        return Summary(aiChars, humanChars, aiLines, humanLines, aiLines + humanLines)
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
