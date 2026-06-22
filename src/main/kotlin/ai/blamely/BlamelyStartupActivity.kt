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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
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

        // Attribution v2 gutter overlay (flag-gated by blamely.attributionV2; inert
        // when off). Paints the active editor from `blamely authorship` — the same
        // working log the commit note flips to (I4).
        project.getService(ai.blamely.authorship.GutterV2Overlay::class.java)?.activate()

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
        val headAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)
        fun pollHead() {
            if (project.isDisposed) return
            headAlarm.addRequest(
                {
                    if (project.isDisposed) return@addRequest
                    val repoRoot = GitUtils.getRepoRoot(project) ?: project.basePath
                    val head = repoRoot?.let { GitUtils.run(it, "rev-parse", "HEAD") }
                    if (head != null && head != lastHead) {
                        lastHead = head
                        project.getService(CliDataService::class.java)?.refresh()
                        refreshUi(project)
                    }
                    pollHead()
                },
                3000
            )
        }
        pollHead()
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
