package ai.blamely.utils

import ai.blamely.core.LineBlame
import java.io.File

object BlankLines {

    fun isBlankLine(text: String): Boolean =
        text.removeSuffix("\r").trim().isEmpty()

    fun filterBlankChangedLines(
        repoRoot: String,
        changedByFile: Map<String, List<Int>>,
    ): Map<String, List<Int>> {
        val out = linkedMapOf<String, List<Int>>()
        for ((filePath, lineNums) in changedByFile) {
            val lines = readFileLines(repoRoot, filePath)
            if (lines == null) {
                out[filePath] = lineNums
                continue
            }
            val kept = mutableListOf<Int>()
            for (ln in lineNums) {
                val text = lines.getOrNull(ln - 1) ?: continue
                if (!isBlankLine(text)) kept.add(ln)
            }
            if (kept.isNotEmpty()) out[filePath] = kept
        }
        return out
    }

    fun stripBlankLineBlame(
        repoRoot: String,
        byFile: Map<String, List<LineBlame>>,
    ): Map<String, List<LineBlame>> {
        val out = linkedMapOf<String, List<LineBlame>>()
        for ((filePath, entries) in byFile) {
            val lines = readFileLines(repoRoot, filePath)
            if (lines == null) {
                out[filePath] = entries
                continue
            }
            val kept = mutableListOf<LineBlame>()
            for (e in entries) {
                val text = lines.getOrNull(e.lineNumber - 1) ?: continue
                if (!isBlankLine(text)) kept.add(e)
            }
            if (kept.isNotEmpty()) out[filePath] = kept
        }
        return out
    }

    private fun readFileLines(repoRoot: String, filePath: String): List<String>? =
        try {
            File(repoRoot, filePath).readLines()
        } catch (_: Exception) {
            null
        }
}
