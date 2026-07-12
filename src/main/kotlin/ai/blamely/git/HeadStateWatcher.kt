package ai.blamely.git

import ai.blamely.cli.CliDataService
import com.intellij.openapi.components.Service
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.Alarm

/**
 * Tracks the repo's HEAD SHA and branch name, reacting to commits and
 * same-SHA branch switches. Extracted from BlamelyStartupActivity's pollHead
 * so the check can ALSO be fired instantly by CliDataWatchService's native
 * `.git/HEAD` file watch — the 3s poll stays as the correctness backstop (it
 * additionally refreshes GitOpState, which the working-log tracker consults
 * synchronously on every keystroke).
 */
@Service(Service.Level.PROJECT)
class HeadStateWatcher(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    @Volatile private var lastHead: String? = null
    @Volatile private var lastBranch: String? = null
    private val checking = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        schedule()
    }

    private fun schedule() {
        if (project.isDisposed) return
        alarm.addRequest({
            checkNow()
            schedule()
        }, 3000)
    }

    /**
     * Runs one HEAD/branch check immediately. Safe to call from any thread and
     * from concurrent triggers (poll tick + VFS HEAD event) — overlapping calls
     * coalesce into one.
     */
    fun checkNow() {
        if (project.isDisposed) return
        if (!checking.compareAndSet(false, true)) return
        try {
            val repoRoot = GitUtils.getRepoRoot(project) ?: project.basePath
            // Refresh the cached git-op / stash-window state the working-log
            // tracker consults synchronously on every change (see GitOpState).
            repoRoot?.let { project.getService(GitOpState::class.java)?.poll(it) }
            val head = repoRoot?.let { GitUtils.run(it, "rev-parse", "HEAD") }
            val branch = repoRoot?.let { GitUtils.getBranchName(it) } ?: "DETACHED"
            if (head != null && head != lastHead) {
                val wasInitial = lastHead == null
                lastHead = head
                lastBranch = branch
                project.getService(CliDataService::class.java)?.refresh()
                refreshUi()
                // A real commit (not the first observation) → drop the trackers'
                // in-memory edits so the next edit re-baselines against the
                // committed content rather than a stale baseline.
                if (!wasInitial) {
                    project.getService(ai.blamely.authorship.WorkingLogTracker::class.java)?.onHeadChanged()
                }
            } else if (head != null && lastBranch != null && branch != lastBranch) {
                // Same HEAD SHA, different branch — `git checkout -b feature` (or
                // switching to an existing branch at the same tip). No commit
                // happened, so the in-memory edits are still live; re-persist them
                // under the NEW branch's working-log dir before a commit there
                // reads it, and refresh so the gutter re-scopes to the branch.
                lastBranch = branch
                project.getService(ai.blamely.authorship.WorkingLogTracker::class.java)?.onBranchChanged()
                project.getService(CliDataService::class.java)?.refresh()
            }
        } finally {
            checking.set(false)
        }
    }

    private fun refreshUi() {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            WindowManager.getInstance().getStatusBar(project)
                ?.updateWidget(ai.blamely.ui.BlamelyStatusBarWidget.WIDGET_ID)
            project.getService(ai.blamely.ui.BlameDecorations::class.java)?.refresh()
        }
    }

    override fun dispose() {}
}
