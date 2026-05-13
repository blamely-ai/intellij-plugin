package ai.blamely.actions

import ai.blamely.core.BlameMapService
import ai.blamely.core.LineBlame
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

class ShowBlameAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = FileEditorManager.getInstance(project).selectedEditor?.file ?: run {
            notify(project, "No file selected")
            return
        }
        val basePath = project.basePath ?: return
        var path = file.path
        if (path.startsWith(basePath)) path = path.substring(basePath.length).trimStart('/', '\\')
        val blameService = project.getService(BlameMapService::class.java) ?: return
        val entries = blameService.blameMap.getBlame(path)
        val ai = entries.count { it.authorType == LineBlame.AuthorType.AI }
        val human = entries.count { it.authorType == LineBlame.AuthorType.HUMAN }
        notify(project, "Blame for $path: $ai AI lines, $human human lines")
    }

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Blamely")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }
}
