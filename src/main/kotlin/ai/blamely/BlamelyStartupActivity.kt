package ai.blamely

import ai.blamely.cli.CliDataService
import ai.blamely.cli.CliHealthNotifier
import ai.blamely.completion.CompletionDetector
import ai.blamely.core.BlameUpdateListener
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
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager

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

        // The status bar shows the session-wide AI%/Human% summary (BlameMap
        // getSummary — same as VS Code); refresh it when the user switches editors
        // so its daemon lamp and counts stay current, mirroring the gutter's own
        // selectionChanged listener and VS Code's onDidChangeActiveTextEditor.
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
        // HeadStateWatcher runs the 3s poll (and also feeds GitOpState); the
        // native `.git/HEAD` watch below fires the same check instantly.
        project.getService(ai.blamely.git.HeadStateWatcher::class.java)?.start()
        // Native file watching (parity with the VS Code plugin's FileSystemWatchers
        // on .git/blamely/working_logs/** and .git/HEAD): an external tool writing
        // an attribution while the IDE is open repaints the gutter within ~200ms
        // instead of waiting for a save or the 30s poll.
        project.getService(ai.blamely.cli.CliDataWatchService::class.java)?.start()

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

        // (The former VFS-only working-log listener lived here; it missed external
        // writes under .git because the subtree was never a watch root nor loaded
        // into the VFS snapshot. Replaced by CliDataWatchService above.)
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
