package ai.blamely.actions

import ai.blamely.git.GitUtils
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

class ShowCommitReportAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val sha = GitUtils.getLatestCommitSha(project) ?: run {
            notify(project, "No commits in repository")
            return
        }
        val cwd = project.basePath ?: return
        val out = GitUtils.run(cwd, "log", "-1", "--show-notes=blamely", "--format=%N", sha) ?: run {
            notify(project, "No Blamely git note for commit ${sha.take(8)}")
            return
        }
        val yamlContent = out.trim()
            .let { raw ->
                if (raw.startsWith("{")) {
                    val sep = raw.indexOf("\n---\n")
                    if (sep >= 0) raw.substring(sep + 5).trim() else raw
                } else raw
            }
            .split("\n---\nblames:")[0]
            .split("\nblames:")[0]
            .trim()
        val dir = GitUtils.getBlamelyDir(project) ?: return
        dir.mkdirs()
        val reportFile = File(dir, "report.yml")
        reportFile.writeText(yamlContent)
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(reportFile)
            if (vf != null) FileEditorManager.getInstance(project).openFile(vf, true)
        }
        notify(project, "Opened report from git note for commit ${sha.take(8)}")
    }

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Blamely")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }
}
