package ai.blamely.actions

import ai.blamely.cli.CliDataService
import ai.blamely.core.BlameMapService
import ai.blamely.core.LineBlame
import ai.blamely.git.GitUtils
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
        val repoRoot = GitUtils.getRepoRoot(project) ?: project.basePath ?: return
        val path = GitUtils.toRepoRelativePath(repoRoot, file.path) ?: run {
            notify(project, "File is outside the git repository")
            return
        }
        project.getService(CliDataService::class.java)?.refresh()
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
