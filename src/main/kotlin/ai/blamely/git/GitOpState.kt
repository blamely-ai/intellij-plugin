// Cached in-progress git-op state, refreshed by the 3s HEAD poll (the IntelliJ
// counterpart of the VS Code plugin's GitOpState — keep both in sync).
//
// Purpose: the working-log tracker must not fold REPLAYED content — a
// cherry-pick/rebase/merge/revert rewriting open buffers, or a stash apply/pop —
// into the working log as fresh Human typing. A per-event `inProgressGitOp`
// check spawns git and is too costly per document change; this service caches:
//   • the five marker files (same set as GitUtils.inProgressGitOp) checked with
//     File.exists() against a once-resolved git dir, and
//   • the stash reflog's mtime — a stash apply/pop leaves NO marker, but always
//     touches .git/logs/refs/stash (or deletes it when the last stash is
//     popped), so ANY transition opens a short "stash window".
// isActive() is synchronous and allocation-free — safe on every document event.
package ai.blamely.git

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File

@Service(Service.Level.PROJECT)
class GitOpState(@Suppress("unused") private val project: Project?) {

    // Test-only: plain construction without a Project.
    constructor() : this(null)

    @Volatile
    private var gitDir: String? = null

    @Volatile
    private var gitDirResolvedFor: String? = null

    @Volatile
    private var markerActive = false

    @Volatile
    private var stashWindowUntilMs = 0L

    @Volatile
    private var lastStashMtimeMs: Long? = null

    @Volatile
    private var stashObserved = false

    /** Refresh the cached state for repoRoot. Called from the HEAD poll (3s) on a
     *  pooled thread — never per keystroke. */
    fun poll(repoRoot: String) {
        if (gitDir == null || gitDirResolvedFor != repoRoot) {
            gitDir = GitUtils.run(repoRoot, "rev-parse", "--path-format=absolute", "--git-dir")
                ?.trim()?.takeIf { it.isNotEmpty() }
            gitDirResolvedFor = repoRoot
        }
        val g = gitDir ?: return
        markerActive = OP_MARKERS.any { File(g, it).exists() }

        val stashLog = File(g, "logs/refs/stash")
        val mtime: Long? = if (stashLog.exists()) stashLog.lastModified() else null
        val prev = lastStashMtimeMs
        // Any TRANSITION of the stash reflog is stash activity: touched (stash/
        // apply), created (first stash), or DELETED (popping the last stash
        // removes the reflog file — mtime goes null, not newer). The first poll
        // only records the baseline.
        val changed = stashObserved && (
            (mtime != null && prev != null && mtime != prev) ||
                (mtime != null && prev == null) ||
                (mtime == null && prev != null)
            )
        if (changed) {
            stashWindowUntilMs = System.currentTimeMillis() + STASH_WINDOW_MS
        }
        lastStashMtimeMs = mtime
        stashObserved = true
    }

    /** True while a marker op is in progress or within the stash window. */
    fun isActive(): Boolean =
        markerActive || System.currentTimeMillis() < stashWindowUntilMs

    companion object {
        private val OP_MARKERS = listOf(
            "CHERRY_PICK_HEAD", "MERGE_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply",
        )

        // How long after a stash-reflog change buffer rewrites still count as
        // replays. The CLI's commit-time reconcile recovers anything the editor
        // mis-folds either way.
        private const val STASH_WINDOW_MS = 10_000L
    }
}
