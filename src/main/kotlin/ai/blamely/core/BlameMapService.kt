package ai.blamely.core

import ai.blamely.git.GitUtils
import ai.blamely.persistence.BlameSerializer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * Project-level service holding the BlameMap for this project.
 * Tracks current branch so stash/branch switch persists old branch blame and loads new branch.
 */
class BlameMapService(val project: Project) {
    val blameMap = BlameMap()

    /** Set after commit to suppress document change tracking briefly so cleared blame isn't re-populated. */
    @Volatile
    var commitSuppressUntil = 0L

    /** Branch we last loaded blame for; when it changes we save to old branch and load new. */
    @Volatile
    var lastLoadedBranch: String? = null
        private set

    fun setLastLoadedBranch(branch: String?) {
        lastLoadedBranch = branch
    }

    /**
     * If current Git branch changed (e.g. user switched branch or created new branch), persist
     * current blame to the old branch folder, then load blame for the new branch. When the new
     * branch has no snapshots yet (e.g. newly created branch), keep current in-memory blame and
     * save it to the new branch so changes "move" with the branch.
     * Never runs blocking git I/O on the EDT — defers to a background thread if called from EDT.
     */
    fun ensureBranchLoaded() {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().executeOnPooledThread { doEnsureBranchLoaded() }
            return
        }
        doEnsureBranchLoaded()
    }

    private fun doEnsureBranchLoaded() {
        val currentBranch = GitUtils.getBranch(project) ?: return
        val last = lastLoadedBranch
        if (last == currentBranch) return
        val cwd = GitUtils.getRepoRoot(project) ?: project.basePath ?: return
        val sessionBefore = Pair(blameMap.firstStartCodingTimeMs, blameMap.totalTimeWaitingForAiMs)
        val hasChanges = GitUtils.hasUncommittedChanges(cwd)
        // Clean working tree: reset line blame; restore session metrics for this branch from session.json
        if (!hasChanges) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                blameMap.clear()
                val s = BlameSerializer.loadSession(project)
                blameMap.restoreSessionMetrics(s.first_start_coding_time_ms, s.total_time_waiting_for_ai_ms)
                lastLoadedBranch = currentBranch
                project.getService(BranchSessionLifecycleService::class.java)?.onBranchChanged(currentBranch)
                refreshStatusBarAfterBranchSwitch()
            }
            return
        }
        val trackedFiles = blameMap.getTrackedFiles().toList()
        val dataToSave = trackedFiles.associateWith { blameMap.getBlame(it) }.filterValues { it.isNotEmpty() }
        if (last != null && dataToSave.isNotEmpty()) {
            BlameSerializer.saveAllToBranch(project, last, dataToSave, blameMap)
        }
        val restored = BlameSerializer.loadAll(project)
        val sessionFromDisk = BlameSerializer.loadSession(project)
        val mergedForApply = if (dataToSave.isNotEmpty()) {
            val m = restored.toMutableMap()
            for ((path, entries) in dataToSave) {
                if (entries.isNotEmpty()) m[path] = entries
            }
            m
        } else {
            restored
        }
        val branchToSet = currentBranch
        val isEmptyNewBranch = restored.isEmpty() && dataToSave.isNotEmpty()
        val dataForNewBranch = if (isEmptyNewBranch) dataToSave else null
        val restoredForApply = if (isEmptyNewBranch) null else mergedForApply
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            if (dataForNewBranch != null && dataForNewBranch.isNotEmpty()) {
                BlameSerializer.saveAllToBranch(project, branchToSet, dataForNewBranch, blameMap)
            }
            if (restoredForApply != null) {
                blameMap.clear()
                restoredForApply.forEach { (path, entries) ->
                    if (entries.isNotEmpty()) blameMap.setFileBlame(path, entries)
                }
                if (dataToSave.isNotEmpty()) {
                    blameMap.restoreSessionMetrics(sessionBefore.first, sessionBefore.second)
                } else {
                    blameMap.restoreSessionMetrics(
                        sessionFromDisk.first_start_coding_time_ms,
                        sessionFromDisk.total_time_waiting_for_ai_ms
                    )
                }
            }
            lastLoadedBranch = branchToSet
            project.getService(BranchSessionLifecycleService::class.java)?.onBranchChanged(branchToSet)
            refreshStatusBarAfterBranchSwitch()
        }
    }

    /** Refresh status bar and tool window after branch switch so counts are correct. Call from EDT. */
    private fun refreshStatusBarAfterBranchSwitch() {
        if (project.isDisposed) return
        com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
            ?.updateWidget(ai.blamely.ui.BlamelyStatusBarWidget.WIDGET_ID)
        project.messageBus.syncPublisher(ai.blamely.core.BlameUpdateListener.TOPIC).blameUpdated()
    }
}
