package ai.blamely.actions

import ai.blamely.git.HookInstaller
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * Restores or removes the Blamely git hook block. Backed-up hooks (created during
 * the original install) are restored verbatim; otherwise the hook file is cleaned
 * (Blamely block stripped) or deleted entirely if it only contained Blamely.
 */
class RestoreGitHookAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val result = HookInstaller.uninstallAll(project)
        notify(
            project,
            "Blamely hooks restored. ${result.message}",
            if (result.ok) NotificationType.INFORMATION else NotificationType.WARNING
        )
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Blamely")
            .createNotification(message, type)
            .notify(project)
    }
}
