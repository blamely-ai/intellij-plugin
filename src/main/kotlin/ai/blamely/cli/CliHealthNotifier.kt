package ai.blamely.cli

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.Alarm
import java.awt.Desktop
import java.net.URI

/** Balloon when oobeya-cli is missing or misconfigured. Notifies once per issue per project session. */
class CliHealthNotifier(private val project: Project) {
    private val log = Logger.getInstance(CliHealthNotifier::class.java)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)
    private var lastStatus: CliHealthStatus? = null

    fun start() {
        evaluate(forceOnStartup = true)
        scheduleNext()
    }

    private fun scheduleNext() {
        if (project.isDisposed) return
        alarm.addRequest(
            {
                if (!project.isDisposed) {
                    evaluate(forceOnStartup = false)
                    scheduleNext()
                }
            },
            30_000
        )
    }

    private fun evaluate(forceOnStartup: Boolean) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val report = CliHealth.check()
            if (report.status == CliHealthStatus.HEALTHY) {
                lastStatus = CliHealthStatus.HEALTHY
                return@executeOnPooledThread
            }
            if (!forceOnStartup && report.status == lastStatus) return@executeOnPooledThread
            lastStatus = report.status
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                showNotification(report)
            }
        }
    }

    private fun showNotification(report: CliHealthReport) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Blamely")
        val content = buildString {
            append(report.message)
            report.detail?.let { append("\n\n").append(it) }
        }
        Notification(
            group.displayId,
            report.title,
            content,
            NotificationType.WARNING
        ).apply {
            addAction(NotificationAction.createSimple("Open install guide") {
                openUrl(report.installUrl)
            })
            addAction(NotificationAction.createSimple("Show fix steps") {
                showFixSteps(report)
            })
        }.notify(project)
    }

    private fun openUrl(url: String) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            }
        } catch (e: Exception) {
            log.warn("Could not open $url", e)
        }
    }

    private fun showFixSteps(report: CliHealthReport) {
        val steps = buildString {
            appendLine(report.message)
            report.detail?.let { appendLine().appendLine(it) }
            appendLine()
            appendLine("Recommended steps:")
            appendLine("1. Install Blamely CLI from https://blamely.ai")
            appendLine("2. Run in terminal: blamely install")
            appendLine("3. Verify: blamely doctor && blamely status")
            appendLine("4. If the daemon fails, check ~/.blamely/daemon.log")
        }
        Messages.showWarningDialog(project, steps, report.title)
    }
}
