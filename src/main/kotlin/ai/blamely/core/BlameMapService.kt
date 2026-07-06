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
    )

    private fun normPath(path: String): String = path.replace('\\', '/')

    fun markPendingAiLines(
        path: String,
        lines: IntRange,
        tool: String?,
        model: String?,
        genType: String?,
        ttlMs: Long = PENDING_AI_TTL_MS,
    ) {
        if (lines.isEmpty()) return
        val key = normPath(path)
        val expiresAt = System.currentTimeMillis() + ttlMs
        synchronized(pendingLock) {
            val byLine = pendingAi.getOrPut(key) { HashMap() }
            for (ln in lines) byLine[ln] = PendingAiLine(tool, model, genType, expiresAt)
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

    // Neutral "detecting authorship…" lines: an AI-likely edit whose author isn't
    // resolved yet. The gutter paints these with the amber ring instead of defaulting
    // to Human and then flipping to AI. Keyed path → (line → expiryMs). Parity with the
    // VS Code plugin's BlameMap.detecting / markDetecting / detectingLinesFor.
    private val detecting = HashMap<String, HashMap<Int, Long>>()

    fun markDetecting(path: String, lines: IntRange, ttlMs: Long = DETECTING_TTL_MS) {
        if (lines.isEmpty()) return
        val key = normPath(path)
        val expiresAt = System.currentTimeMillis() + ttlMs
        synchronized(pendingLock) {
            val byLine = detecting.getOrPut(key) { HashMap() }
            for (ln in lines) byLine[ln] = expiresAt
        }
    }

    fun detectingLinesFor(path: String): Set<Int> = synchronized(pendingLock) {
        pruneExpired()
        detecting[normPath(path)]?.keys?.toSet() ?: emptySet()
    }

    fun clearDetectingLine(path: String, line: Int) = synchronized(pendingLock) {
        val key = normPath(path)
        detecting[key]?.let { byLine ->
            byLine.remove(line)
            if (byLine.isEmpty()) detecting.remove(key)
        }
        Unit
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        val pathIt = pendingAi.iterator()
        while (pathIt.hasNext()) {
            val byLine = pathIt.next().value
            byLine.entries.removeAll { it.value.expiresAtMs <= now }
            if (byLine.isEmpty()) pathIt.remove()
        }
        val detIt = detecting.iterator()
        while (detIt.hasNext()) {
            val byLine = detIt.next().value
            byLine.entries.removeAll { it.value <= now }
            if (byLine.isEmpty()) detIt.remove()
        }
    }

    companion object {
        /** Backstop lifetime for a pending ("detecting") AI line if SQLite never
         *  confirms it. A chat/agent apply is recorded only after the editor writes
         *  its chat-session log and the daemon's watcher reads it, which can lag the
         *  on-screen edit by tens of seconds — so keep the loading state that whole
         *  window instead of flashing Human first. Re-armed on each streamed chunk.
         *  5s: Copilot Chat is now recorded in real time (transcript watcher) and
         *  Cursor/Copilot-CLI via hooks, so this only bridges the short watcher
         *  latency, not the old lazy-flush lag. */
        const val PENDING_AI_TTL_MS: Long = 5_000

        /** Lifetime of the neutral "detecting" gutter state before it prunes back to
         *  the resolved author. Re-armed while an agent apply is in flight; matches the
         *  VS Code plugin's DETECTING_TTL. */
        const val DETECTING_TTL_MS: Long = 8_000
    }
}
