package ai.blamely.core

import ai.blamely.git.GitUtils
import ai.blamely.persistence.BlamelyRepoPaths
import ai.blamely.persistence.BlamelyUserRepoPaths
import ai.blamely.persistence.HomeBranchSession
import com.intellij.openapi.project.Project
import java.io.File

/** One row for the Changes tool window: archived trace folders under `closed/<commit>/trace/`. */
data class BranchSessionListEntry(
    val sessionId: String,
    val status: String,
    val openedAt: String,
    val updatedAt: String,
    val closedAt: String?,
    val commitSha: String?,
    val stashLinkCount: Int
)

/**
 * Shared layout: one `trace/` + `report.yml` per branch under ~/.blamely/repos/.../branches/<branch>/
 * (CLI and IDE). No per-UUID open/stash/closed JSON sessions. On commit, trace files archive to
 * `closed/<commitSha>/trace/` (same as blamely-cli hook).
 */
class BranchSessionLifecycleService(private val project: Project) {

    fun initializeOnStartup() {
        if (project.isDisposed) return
        GitUtils.getRepoRoot(project) ?: return
        GitUtils.getBranch(project)
    }

    fun onBranchChanged(@Suppress("UNUSED_PARAMETER") branch: String) {
        /* Branch-specific paths are resolved on demand from Git. */
    }

    fun ensureOpenSessionOnCodeWork() {
        /* Single active trace directory; no separate session records. */
    }

    fun touchSession() {}

    fun pollStashAndLink() {}

    fun closeSessionAfterCommit(commitSha: String, @Suppress("UNUSED_PARAMETER") commitNoteAttached: Boolean) {
        if (project.isDisposed) return
        val rootStr = GitUtils.getRepoRoot(project) ?: return
        val branch = GitUtils.getBranch(project)
        BlamelyUserRepoPaths.archiveBranchTraceToClosed(File(rootStr), branch, commitSha)
    }

    fun listSessionsForToolWindow(): List<BranchSessionListEntry> {
        if (project.isDisposed) return emptyList()
        val root = GitUtils.getRepoRoot(project) ?: return emptyList()
        val branch = GitUtils.getBranch(project) ?: return emptyList()
        val data = BlamelyUserRepoPaths.resolveBlamelyDataDir(File(root)) ?: return emptyList()
        val bk = BlamelyRepoPaths.safeBranchName(branch)
        val closedRoot = File(File(File(data, "branches"), bk), "closed")
        if (!closedRoot.isDirectory) return emptyList()
        return closedRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.map { dir ->
                val sha = dir.name
                BranchSessionListEntry(
                    sessionId = sha,
                    status = HomeBranchSession.STATUS_CLOSED,
                    openedAt = "",
                    updatedAt = "",
                    closedAt = null,
                    commitSha = sha,
                    stashLinkCount = 0
                )
            }.orEmpty()
    }
}
