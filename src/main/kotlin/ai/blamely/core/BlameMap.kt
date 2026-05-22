package ai.blamely.core

/**
 * Per-file line blame map. Tracks which lines are AI vs human authored.
 * Same semantics as the Blamely VS Code BlameMap (95% rule, charsPerLine ceiling, etc.).
 */
class BlameMap {
    private val map = mutableMapOf<String, MutableList<LineBlame>>()

    private fun normPath(path: String): String = path.replace('\\', '/')

    private fun resolveAuthorTypeFromChars(
        aiChars: Int,
        humanChars: Int,
        interactionType: String?,
        codingType: LineBlame.CodingType
    ): LineBlame.AuthorType {
        val total = aiChars + humanChars
        if (total <= 0) return LineBlame.AuthorType.HUMAN
        if (aiChars > humanChars) return LineBlame.AuthorType.AI
        if (humanChars > aiChars) return LineBlame.AuthorType.HUMAN
        val cliBulk = (interactionType?.startsWith("blamely-cli-") == true) &&
            codingType == LineBlame.CodingType.BULK_INSERT
        return if (cliBulk) LineBlame.AuthorType.HUMAN else LineBlame.AuthorType.AI
    }

    private fun shouldPreserveIdeBlameOverDiskSnapshot(m: LineBlame, d: LineBlame?): Boolean {
        if (m.authorType == LineBlame.AuthorType.HUMAN) return true
        if (m.humanChars > m.aiChars) return true
        val mCli = m.interactionType?.startsWith("blamely-cli-") == true
        if (!mCli && m.interactionType != null) return true
        if (m.codingType == LineBlame.CodingType.TYPING && m.humanChars > 0) return true
        if (d?.interactionType?.startsWith("blamely-cli-") == true && m.codingType == LineBlame.CodingType.TYPING) return true
        if (d?.interactionType?.startsWith("blamely-cli-") == true &&
            (m.aiChars != d.aiChars || m.humanChars != d.humanChars)
        ) {
            return true
        }
        return false
    }

    /** Tracks which old-file line numbers were deleted by AI (so commit can attribute them as AI, not human). */
    private val aiDeletedLines = mutableMapOf<String, MutableSet<Int>>()

    /** Total ms from user triggering AI until first AI edit (time waiting for AI). */
    @Volatile var totalTimeWaitingForAiMs: Long = 0L
        private set

    /** Epoch ms when user first started coding (first attributed change) in this session. */
    @Volatile var firstStartCodingTimeMs: Long = 0L
        private set

    fun recordFirstStartCodingTimeIfNeeded() {
        if (firstStartCodingTimeMs == 0L) firstStartCodingTimeMs = System.currentTimeMillis()
    }

    /** Restore metrics from `.git/blamely/snapshots/<branch>/session.json` or after a branch switch. */
    fun restoreSessionMetrics(firstStartCodingTimeMs: Long, totalTimeWaitingForAiMs: Long) {
        this.firstStartCodingTimeMs = firstStartCodingTimeMs
        this.totalTimeWaitingForAiMs = totalTimeWaitingForAiMs
    }

    fun addTimeWaitingForAi(deltaMs: Long) {
        if (deltaMs > 0) totalTimeWaitingForAiMs += deltaMs
    }

    fun recordAiDeletion(filePath: String, startLineOldFile: Int, deletedLineCount: Int) {
        val set = aiDeletedLines.getOrPut(filePath.replace('\\', '/')) { mutableSetOf() }
        for (line in startLineOldFile until (startLineOldFile + deletedLineCount)) {
            set.add(line)
        }
    }

    fun wasLineDeletedByAi(filePath: String, oldLineNumber: Int): Boolean {
        val norm = filePath.replace('\\', '/')
        return aiDeletedLines[norm]?.contains(oldLineNumber) == true
    }

    fun clearAiDeletionTracking(filePath: String) {
        aiDeletedLines.remove(filePath.replace('\\', '/'))
    }

    /**
     * Reduce character counts when content is deleted (or undone). Each deleted line's blame entry
     * is reduced by that line's deleted length; entries that go to 0 are removed.
     */
    fun decrementCharsForDeletion(filePath: String, startLine: Int, oldFragment: CharSequence) {
        val key = normPath(filePath)
        val list = map[key] ?: return
        val lines = oldFragment.split("\n")
        for (i in lines.indices) {
            val lineChars = lines[i].length
            if (lineChars <= 0) continue
            val lineNum = startLine + i
            val entry = list.find { it.lineNumber == lineNum } ?: continue
            val total = entry.aiChars + entry.humanChars
            if (total <= 0) continue
            val toRemove = lineChars.coerceAtMost(total)
            // Prefer human chars first so backspace after manual typing on an AI line restores AI gutter.
            val humanReduce = toRemove.coerceAtMost(entry.humanChars)
            val aiReduce = (toRemove - humanReduce).coerceAtMost(entry.aiChars)
            entry.humanChars = (entry.humanChars - humanReduce).coerceAtLeast(0)
            entry.aiChars = (entry.aiChars - aiReduce).coerceAtLeast(0)
            entry.authorType = resolveAuthorTypeFromChars(
                entry.aiChars,
                entry.humanChars,
                entry.interactionType,
                entry.codingType
            )
            if (entry.aiChars == 0 && entry.humanChars == 0) {
                list.remove(entry)
            }
        }
        list.sortBy { it.lineNumber }
    }

    fun setAttribute(
        filePath: String,
        lineStart: Int,
        lineEnd: Int,
        authorType: LineBlame.AuthorType,
        provider: String? = null,
        model: String? = null,
        prompt: String? = null,
        interactionType: String? = null,
        timestamp: String? = null,
        charsInserted: Int = 0,
        charsPerLineOverride: List<Int>? = null,
        codingType: LineBlame.CodingType = LineBlame.CodingType.TYPING
    ): List<LineBlame> {
        val key = normPath(filePath)
        val list = map.getOrPut(key) { mutableListOf() }
        val ts = timestamp ?: java.time.Instant.now().toString()
        val lineCount = (lineEnd - lineStart + 1).coerceAtLeast(1)
        // Use actual chars per line from fragment when provided; else distribute so total = charsInserted
        val affected = mutableListOf<LineBlame>()

        for ((index, line) in (lineStart..lineEnd).withIndex()) {
            var charsThisLine = when {
                charsPerLineOverride != null && index < charsPerLineOverride.size -> charsPerLineOverride[index].coerceAtLeast(0)
                charsPerLineOverride != null -> 0
                else -> {
                    val base = charsInserted / lineCount
                    val remainder = charsInserted % lineCount
                    base + (if (index < remainder) 1 else 0)
                }
            }
            val isNewLine = list.none { it.lineNumber == line }
            // New lines with 0 chars (e.g. pressing Enter) count as 1 char each so status bar is correct
            if (charsThisLine <= 0 && isNewLine) {
                charsThisLine = 1
            } else if (charsThisLine <= 0) {
                continue
            }
            val idx = list.indexOfFirst { it.lineNumber == line }
            if (idx >= 0) {
                val entry = list[idx]
                if (authorType == LineBlame.AuthorType.AI) {
                    entry.aiChars += charsThisLine
                } else {
                    entry.humanChars += charsThisLine
                }
                val total = entry.aiChars + entry.humanChars
                entry.authorType = resolveAuthorTypeFromChars(
                    entry.aiChars,
                    entry.humanChars,
                    entry.interactionType,
                    entry.codingType
                )
                if (entry.authorType == LineBlame.AuthorType.AI) {
                    entry.provider = provider ?: entry.provider
                    entry.model = model ?: entry.model
                    entry.prompt = prompt ?: entry.prompt
                    entry.interactionType = interactionType ?: entry.interactionType
                } else {
                    entry.provider = null
                    entry.model = null
                    entry.prompt = null
                    entry.interactionType = null
                    if (authorType == LineBlame.AuthorType.HUMAN || codingType == LineBlame.CodingType.TYPING) {
                        entry.codingType = LineBlame.CodingType.TYPING
                    }
                }
                if (codingType != LineBlame.CodingType.TYPING) {
                    entry.codingType = codingType
                }
                entry.timestamp = ts
                affected.add(entry)
            } else {
                val blame = LineBlame(
                    lineNumber = line,
                    authorType = authorType,
                    provider = provider,
                    timestamp = ts,
                    model = model,
                    prompt = prompt,
                    interactionType = if (authorType == LineBlame.AuthorType.AI) interactionType else null,
                    aiChars = if (authorType == LineBlame.AuthorType.AI) charsThisLine else 0,
                    humanChars = if (authorType == LineBlame.AuthorType.HUMAN) charsThisLine else 0,
                    codingType = codingType,
                    ide = ai.blamely.utils.IdeLabel.current()
                )
                val total = blame.aiChars + blame.humanChars
                blame.authorType = if (total > 0 && blame.aiChars > blame.humanChars) {
                    LineBlame.AuthorType.AI
                } else {
                    LineBlame.AuthorType.HUMAN
                }
                list.add(blame)
                affected.add(blame)
            }
        }
        // Keep one entry per line number (the one with most chars) so we never double-count
        dedupeEntriesByLineNumber(list)
        list.sortBy { it.lineNumber }
        return affected
    }

    /** Remove duplicate line numbers, keeping the entry with the highest aiChars+humanChars per line. */
    private fun dedupeEntriesByLineNumber(list: MutableList<LineBlame>) {
        val byLine = list.groupBy { it.lineNumber }
        list.clear()
        byLine.values.forEach { lineEntries ->
            list.add(lineEntries.maxByOrNull { it.aiChars + it.humanChars }!!)
        }
    }

    fun getBlame(filePath: String): List<LineBlame> = map[normPath(filePath)] ?: map[filePath] ?: emptyList()
    fun getTrackedFiles(): List<String> = map.keys.toList()
    fun getRawMap(): Map<String, List<LineBlame>> = map.toMap()

    /**
     * Reindex blame after insert/delete so line numbers shift (mirrors VS Code BlameIndex.reindex).
     * - No-op when netChange == 0 && linesInserted == 0 (pure same-length replacement).
     * - Entries in [startLine, startLine + deletedLineCount - 1] are removed.
     * - Entries at startLine + deletedLineCount and above shift by (insertedLineCount - deletedLineCount).
     */
    fun reindex(filePath: String, startLine: Int, insertedLineCount: Int, deletedLineCount: Int) {
        val netChange = insertedLineCount - deletedLineCount
        if (netChange == 0 && insertedLineCount == 0) return
        val list = map[normPath(filePath)] ?: return
        val endDeleted = startLine + deletedLineCount - 1
        list.removeAll { it.lineNumber in startLine..endDeleted }
        if (netChange != 0) {
            list.filter { it.lineNumber >= startLine + deletedLineCount }.forEach { it.lineNumber += netChange }
        }
        list.sortBy { it.lineNumber }
    }

    /**
     * Mutate given entries to AI attribution (mirrors VS Code batch re-attribution when any in sequence matched AI).
     */
    fun reattributeToAi(entries: List<LineBlame>, provider: String?, model: String?) {
        entries.forEach { b ->
            if (b.authorType != LineBlame.AuthorType.AI) {
                b.aiChars += b.humanChars
                b.humanChars = 0
            }
            b.authorType = LineBlame.AuthorType.AI
            if (provider != null) b.provider = provider
            if (model != null) b.model = model
        }
    }

    fun setCommitSha(commitSha: String) {
        map.values.forEach { entries ->
            entries.forEach { if (it.commitSha == null) it.commitSha = commitSha }
        }
    }

    /** Set commit_sha only on entries in the given files (for "current commit changes only"). */
    fun setCommitShaForFiles(commitSha: String, filePaths: Set<String>) {
        if (filePaths.isEmpty()) return
        val normalized = filePaths.map { it.replace('\\', '/') }.toSet()
        for ((path, entries) in map) {
            val norm = path.replace('\\', '/')
            if (norm in normalized) {
                entries.forEach { if (it.commitSha == null) it.commitSha = commitSha }
            }
        }
    }

    /**
     * Set commit_sha only on entries whose line numbers are in [lineNumbers] for the given file.
     * This ensures only lines actually changed in the commit are tagged.
     */
    fun setCommitShaForLines(commitSha: String, filePath: String, lineNumbers: Set<Int>) {
        if (lineNumbers.isEmpty()) return
        val entries = map[normPath(filePath)] ?: return
        entries.forEach { entry ->
            if (entry.lineNumber in lineNumbers && entry.commitSha == null) {
                entry.commitSha = commitSha
            }
        }
    }

    fun setFileBlame(filePath: String, entries: List<LineBlame>) {
        map[normPath(filePath)] = entries.toMutableList()
    }

    /** Remove blame for a file (e.g. when file is deleted). Recalculate status bar after. */
    fun removeFile(filePath: String) {
        val key = normPath(filePath)
        map.remove(key)
        aiDeletedLines.remove(key)
    }

    /** Transfer blame from old path to new path (file move/rename). */
    fun moveFile(oldPath: String, newPath: String) {
        val oldKey = normPath(oldPath)
        val newKey = normPath(newPath)
        val entries = map.remove(oldKey) ?: return
        entries.forEach { it.codingType = LineBlame.CodingType.TYPING }
        map[newKey] = entries
        aiDeletedLines.remove(oldKey)?.let { aiDeletedLines[newKey] = it }
    }

    fun clear() {
        map.clear()
        aiDeletedLines.clear()
        totalTimeWaitingForAiMs = 0L
        firstStartCodingTimeMs = 0L
    }

    /**
     * Summary counts ADD attribution lines currently in the map (merges by normalized path and line).
     * Rows with commitSha set are included so CLI / disk snapshots (HEAD at trace end) update the status bar.
     * DELETE rows are excluded.
     */
    fun getSummary(): BlameSummary {
        var total = 0
        var ai = 0
        var totalAiChars = 0
        var totalHumanChars = 0
        val providerCounts = mutableMapOf<String, Int>()
        val byNormPath = map.entries.groupBy { normPath(it.key) }.mapValues { (_, keyToEntries) ->
            keyToEntries.flatMap { it.value }.filter { it.changeType != LineBlame.ChangeType.DELETE }
        }
        for (entries in byNormPath.values) {
            for (lineEntries in entries.groupBy { it.lineNumber }.values) {
                val best = lineEntries.maxByOrNull { it.aiChars + it.humanChars } ?: continue
                total += 1
                totalAiChars += best.aiChars
                totalHumanChars += best.humanChars
                if (best.authorType == LineBlame.AuthorType.AI) {
                    ai += 1
                    best.provider?.let { p -> providerCounts[p] = (providerCounts[p] ?: 0) + 1 }
                }
            }
        }
        return BlameSummary(total, ai, total - ai, totalAiChars, totalHumanChars, providerCounts)
    }

    data class BlameSummary(
        val totalLines: Int,
        val aiLines: Int,
        val humanLines: Int,
        val aiChars: Int,
        val humanChars: Int,
        val providerCounts: Map<String, Int>
    )
}
