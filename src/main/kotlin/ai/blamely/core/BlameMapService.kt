package ai.blamely.core

import com.intellij.openapi.project.Project

/** Project-level in-memory blame view populated from oobeya-cli SQLite. */
class BlameMapService(val project: Project) {
    val blameMap = BlameMap()

    /**
     * Wall-clock ms of the most recent optimistic AI paint (CompletionDetector.
     * pushImmediateBlame). CliDataService.refresh() captures the time it began
     * loading data and SKIPS its destructive clear+rebuild if an optimistic
     * paint happened afterwards — that paint is fresher than the refresh's data,
     * so applying the refresh would momentarily clobber the AI gutter back to
     * Human (an AI→Human→AI flicker) until the next refresh restores it.
     */
    @Volatile
    var lastOptimisticPaintMs: Long = 0

    /**
     * Recently accepted AI lines that the daemon may not have persisted to SQLite
     * yet. [CliDataService.refresh] re-asserts these so the gutter never downgrades
     * AI→Human in the window between a completion/chat accept and the daemon writing
     * the row. Entries are cleared once a refresh confirms the line as AI, or after
     * [PENDING_AI_TTL_MS].
     */
    private val pendingLock = Any()
    private val pendingAi = HashMap<String, HashMap<Int, PendingAiLine>>()

    data class PendingAiLine(
        val tool: String?,
        val model: String?,
        val genType: String?,
        val expiresAtMs: Long,
        /**
         * sha256 of the AI line text captured at accept time. The pending overlay
         * is keyed by line NUMBER, which does not survive a human inserting a line
         * in the middle of the band; this sha lets the reader confirm the current
         * line still holds the AI text before painting it AI. Null only for blank
         * lines (no sha captured).
         */
        val contentSha: String? = null,
    )

    private fun normPath(path: String): String = path.replace('\\', '/')

    fun markPendingAiLines(
        path: String,
        lines: IntRange,
        tool: String?,
        model: String?,
        genType: String?,
        ttlMs: Long = PENDING_AI_TTL_MS,
        lineShas: Map<Int, String>? = null,
    ) {
        if (lines.isEmpty()) return
        val key = normPath(path)
        val expiresAt = System.currentTimeMillis() + ttlMs
        synchronized(pendingLock) {
            val byLine = pendingAi.getOrPut(key) { HashMap() }
            for (ln in lines) byLine[ln] = PendingAiLine(tool, model, genType, expiresAt, lineShas?.get(ln))
        }
    }

    fun pendingAiPaths(): List<String> = synchronized(pendingLock) {
        pruneExpired()
        pendingAi.keys.toList()
    }

    fun pendingAiLinesFor(path: String): Map<Int, PendingAiLine> = synchronized(pendingLock) {
        pruneExpired()
        pendingAi[normPath(path)]?.toMap() ?: emptyMap()
    }

    fun clearPendingAiLine(path: String, line: Int) = synchronized(pendingLock) {
        val key = normPath(path)
        pendingAi[key]?.let { byLine ->
            byLine.remove(line)
            if (byLine.isEmpty()) pendingAi.remove(key)
        }
        Unit
    }

    fun clearAllPendingAi() = synchronized(pendingLock) {
        pendingAi.clear()
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        val pathIt = pendingAi.iterator()
        while (pathIt.hasNext()) {
            val byLine = pathIt.next().value
            byLine.entries.removeAll { it.value.expiresAtMs <= now }
            if (byLine.isEmpty()) pathIt.remove()
        }
    }

    companion object {
        /** Backstop lifetime for a pending AI line if SQLite never confirms it (e.g. daemon down). */
        const val PENDING_AI_TTL_MS: Long = 12_000
    }
}
