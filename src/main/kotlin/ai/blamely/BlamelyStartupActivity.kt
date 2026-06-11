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
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.Alarm

/**
 * Starts read-only blamely CLI data polling and wires UI refresh on blame updates.
 */
class BlamelyStartupActivity : StartupActivity, DumbAware {

    override fun runActivity(project: Project) {
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

        cliData.start()
        CliHealthNotifier(project).start()
        project.getService(ai.blamely.ui.BlameDecorations::class.java)?.refresh()

        if (BlamelySettings.getInstance().detectInlineCompletion) {
            project.getService(CompletionDetector::class.java)?.register()
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
