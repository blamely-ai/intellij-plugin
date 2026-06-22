// Editor live-tracker core — Kotlin port of tracker.ts. Accumulates a file's
// working log IN MEMORY across observed changes (typing=Human, completion/apply=AI),
// keeping the baseline as the file's last-known content so no checkpoint is needed.
// The IntelliJ layer creates one per open document, calls applyEdit on each change
// with the classified author, and flushes current() to the store. Pure (no platform
// deps) → unit-tested directly; shares the single attribute() engine.
package ai.blamely.authorship

class FileTracker(baseline: String, priorLog: WorkingLog? = null) {
    private var log: WorkingLog? = priorLog
    private var lastContent: String = baseline
    private var dirty = false

    /** Fold one observed change into the in-memory log: diff last-known content
     *  against [newContent] and attribute changed lines to [author]; unchanged
     *  lines keep their prior author. Feed changes in observed order. */
    fun applyEdit(newContent: String, author: Author) {
        if (newContent == lastContent) return // no-op (e.g. a save with no edit)
        log = attribute(log, lastContent, newContent, author)
        lastContent = newContent
        dirty = true
    }

    fun current(): WorkingLog? = log
    fun content(): String = lastContent
    fun isDirty(): Boolean = dirty
    fun markFlushed() { dirty = false }
}
