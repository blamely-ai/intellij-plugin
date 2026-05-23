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

    fun getSummary(): Summary {
        var aiChars = 0
        var humanChars = 0
        var aiLines = 0
        var humanLines = 0
        for ((_, entries) in map) {
            val byLine = linkedMapOf<Int, LineBlame>()
            for (e in entries) {
                if (e.changeType == LineBlame.ChangeType.DELETE) continue
                val existing = byLine[e.lineNumber]
                val eTotal = e.aiChars + e.humanChars
                val curTotal = existing?.let { it.aiChars + it.humanChars } ?: 0
                if (existing == null || eTotal >= curTotal) {
                    byLine[e.lineNumber] = e
                }
            }
            for (e in byLine.values) {
                aiChars += e.aiChars
                humanChars += e.humanChars
                if (e.authorType == LineBlame.AuthorType.AI) aiLines++ else humanLines++
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
