package ai.blamely.utils

import ai.blamely.core.SuggestionRecord

/**
 * Matches inserted text against pending suggestions (mirrors VS Code DiffMatcher.ts).
 * Used to attribute AI when the user accepts a suggestion that was recorded in TraceStore.
 */
data class MatchResult(
    val suggestion: SuggestionRecord,
    val similarity: Double
)

@Suppress("UNUSED_PARAMETER")
fun matchSuggestion(
    pendingSuggestions: List<SuggestionRecord>,
    insertedText: String,
    filePath: String,
    position: Pair<Int, Int> // line, character (kept for VS Code API compatibility)
): MatchResult? {
    val trimmed = insertedText.trim()
    if (trimmed.isEmpty()) return null

    // 1. Exact match
    for (s in pendingSuggestions) {
        if (s.filePath == filePath && s.suggestedText == insertedText) {
            return MatchResult(s, 1.0)
        }
    }

    // 2. Normalized whitespace match
    val normalizedInserted = normalizeWhitespace(insertedText)
    for (s in pendingSuggestions) {
        if (s.filePath == filePath && normalizeWhitespace(s.suggestedText) == normalizedInserted) {
            return MatchResult(s, 0.95)
        }
    }

    // 3. Fuzzy match (Levenshtein similarity >= 0.8)
    var bestMatch: MatchResult? = null
    for (s in pendingSuggestions) {
        if (s.filePath != filePath) continue
        val sim = similarity(s.suggestedText, insertedText)
        if (sim >= 0.8 && (bestMatch == null || sim > bestMatch.similarity)) {
            bestMatch = MatchResult(s, sim)
        }
    }
    return bestMatch
}

private fun normalizeWhitespace(text: String): String =
    text.replace(Regex("\\s+"), " ").trim()

private fun levenshteinDistance(a: String, b: String): Int {
    val m = a.length
    val n = b.length
    if (m == 0) return n
    if (n == 0) return m

    var prev = IntArray(n + 1) { it }
    var curr = IntArray(n + 1)
    for (i in 1..m) {
        curr[0] = i
        for (j in 1..n) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(
                curr[j - 1] + 1,
                prev[j] + 1,
                prev[j - 1] + cost
            )
        }
        val t = prev; prev = curr; curr = t
    }
    return prev[n]
}

fun similarity(a: String, b: String): Double {
    val maxLen = maxOf(a.length, b.length)
    if (maxLen == 0) return 1.0
    val dist = levenshteinDistance(a, b)
    return 1.0 - dist.toDouble() / maxLen
}
