package ai.blamely.actions

import ai.blamely.git.HookInstaller
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * Installs Blamely's Git hooks via [HookInstaller]: Node **hookRunner.js** (VS Code parity)
 * plus the pre-push notes helper script under `.git/blamely/`.
 */
class InstallHookAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val result = HookInstaller.installAll(project)
        notify(
            project,
            "Blamely hooks installed. ${result.message}",
            if (result.ok) NotificationType.INFORMATION else NotificationType.WARNING
        )
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Blamely")
            .createNotification(message, type)
            .notify(project)
    }
}
