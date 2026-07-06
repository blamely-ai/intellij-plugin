package ai.blamely

import ai.blamely.cli.CliDataService
import ai.blamely.cli.CliHealthNotifier
import ai.blamely.completion.CompletionDetector
import ai.blamely.core.BlameUpdateListener
import ai.blamely.git.GitUtils
import ai.blamely.settings.BlamelySettings
import ai.blamely.ui.BlamelyStatusBarWidget
import ai.blamely.utils.BlamelyLogger
import ai.blamely.utils.BlamelyPluginInfo
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.Alarm

/**
 * Starts read-only blamely CLI data polling and wires UI refresh on blame updates.
 *
 * Implements [ProjectActivity] (not the old StartupActivity, which IntelliJ 2026.1 /
 * build 261 removed — registering a StartupActivity there throws "Migrate … to
 * ProjectActivity" and the activity never runs). execute() is a suspend fun invoked
 * on a background coroutine after the project opens; all UI work below already hops
 * to the EDT via invokeLater, so nothing here needs the EDT directly.
 */
class BlamelyStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (project.isDefault || project.basePath == null) return

        // Log the running plugin version so it's unambiguous in idea.log which build
        // is actually loaded (installing a .zip without a full IDE restart keeps the
        // OLD classes loaded — a common cause of "my fix didn't take effect").
        val version = BlamelyPluginInfo.readVersion(BlamelyStartupActivity::class.java)
        BlamelyLogger.info("Blamely plugin version $version active for ${project.name}")

        val cliData = project.getService(CliDataService::class.java) ?: return

        project.messageBus.connect(project).subscribe(
            BlameUpdateListener.TOPIC,
            object : BlameUpdateListener {
                override fun blameUpdated() {
                    refreshUi(project)
                }
            }
        )

        // The status bar count is scoped to the ACTIVE FILE, so refresh it when the
        // user switches editors — mirrors the gutter's own selectionChanged listener
        // and VS Code's onDidChangeActiveTextEditor. Without this the bar would keep
        // showing the previous file's numbers until the next blame update.
        project.messageBus.connect(project).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    refreshUi(project)
                }
            }
        )

        cliData.start()
        CliHealthNotifier(project).start()
        project.getService(ai.blamely.ui.BlameDecorations::class.java)?.refresh()

        // (The Attribution v2 gutter is painted by BlameDecorations from the BlameMap,
        // which CliDataService.refreshV2 fills via `blamely authorship`. The former
        // GutterV2Overlay re-fetched the same active-editor authorship and re-painted
        // on every keystroke/blame-update — pure duplication — so it was removed.)

        if (BlamelySettings.getInstance().detectInlineCompletion) {
            val detector = project.getService(CompletionDetector::class.java)
            // Attribution v2 (flag-gated in the tracker): feed every classified
            // change into the working-log tracker. No-op for attribution output
            // until the Phase 3 flip; safe to wire unconditionally.
            val workingLog = project.getService(ai.blamely.authorship.WorkingLogTracker::class.java)
            if (detector != null && workingLog != null) {
                detector.onEditObserved = { absPath, prev, next, author -> workingLog.onEdit(absPath, prev, next, author) }
            }
            detector?.register()
            // Detects Copilot agent-mode / chat file writes (new files + rewrites)
            // that bypass the action system — see AgentEditDetector.
            project.getService(ai.blamely.completion.AgentEditDetector::class.java)?.register()
        }

        // Refresh history when HEAD changes (blamely writes git notes on commit).
        var lastHead: String? = null
        var lastBranch: String? = null
        val headAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)
        fun pollHead() {
            if (project.isDisposed) return
            headAlarm.addRequest(
                {
                    if (project.isDisposed) return@addRequest
                    val repoRoot = GitUtils.getRepoRoot(project) ?: project.basePath
                    // Refresh the cached git-op / stash-window state the working-log
                    // tracker consults synchronously on every change (see GitOpState).
                    repoRoot?.let { project.getService(ai.blamely.git.GitOpState::class.java)?.poll(it) }
                    val head = repoRoot?.let { GitUtils.run(it, "rev-parse", "HEAD") }
                    val branch = repoRoot?.let { GitUtils.getBranchName(it) } ?: "DETACHED"
                    if (head != null && head != lastHead) {
                        val wasInitial = lastHead == null
                        lastHead = head
                        lastBranch = branch
                        project.getService(CliDataService::class.java)?.refresh()
                        refreshUi(project)
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
                    pollHead()
                },
                3000
            )
        }
        pollHead()

        // Focus-loss flush (Decision B): when the IDE is deactivated (user switches to a
        // terminal to commit, etc.) persist pending working-log edits NOW, before a
        // commit reads them.
        ApplicationManager.getApplication().messageBus.connect(project).subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationDeactivated(ideFrame: IdeFrame) {
                    if (project.isDisposed) return
                    project.getService(ai.blamely.authorship.WorkingLogTracker::class.java)?.flushAll()
                }
            },
        )

        // Save-flush + close-evict for the working-log tracker (parity with the VS Code
        // plugin's onDidSaveTextDocument / onDidCloseTextDocument): persist the latest
        // state before a commit reads it, and drop a file's tracker when its editor closes
        // so it doesn't linger in memory.
        @Suppress("DEPRECATION")
        ApplicationManager.getApplication().messageBus.connect(project).subscribe(
            com.intellij.AppTopics.FILE_DOCUMENT_SYNC,
            object : com.intellij.openapi.fileEditor.FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: com.intellij.openapi.editor.Document) {
                    if (project.isDisposed) return
                    val path = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                        .getFile(document)?.takeIf { it.isInLocalFileSystem }?.path ?: return
                    project.getService(ai.blamely.authorship.WorkingLogTracker::class.java)?.flushFile(path)
                }
            },
        )
        project.messageBus.connect(project).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(
                    source: com.intellij.openapi.fileEditor.FileEditorManager,
                    file: com.intellij.openapi.vfs.VirtualFile,
                ) {
                    if (project.isDisposed) return
                    project.getService(ai.blamely.authorship.WorkingLogTracker::class.java)?.dropFile(file.path)
                }
            },
        )

        // Working-log dir watcher (parity with the VS Code plugin's FileSystemWatcher on
        // .git/blamely/working_logs): refresh promptly when attribution files change,
        // complementing the periodic poll. Best-effort — IntelliJ's VFS may not observe
        // every external write under .git, so this augments rather than replaces the poll.
        val wlRefreshAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)
        ApplicationManager.getApplication().messageBus.connect(project).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (project.isDisposed) return
                    val hit = events.any {
                        it.path.replace('\\', '/').contains("/.git/blamely/working_logs/")
                    }
                    if (hit) {
                        wlRefreshAlarm.cancelAllRequests()
                        wlRefreshAlarm.addRequest({
                            if (!project.isDisposed) project.getService(CliDataService::class.java)?.refresh()
                        }, 200)
                    }
                }
            },
        )
    }

    private fun refreshUi(project: Project) {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            WindowManager.getInstance().getStatusBar(project)
                ?.updateWidget(BlamelyStatusBarWidget.WIDGET_ID)
            project.getService(ai.blamely.ui.BlameDecorations::class.java)?.refresh()
        }
    }
}
