package ai.blamely.actions

import ai.blamely.core.BlameMapService
import ai.blamely.core.TraceStoreService
import ai.blamely.git.GitUtils
import ai.blamely.persistence.BlameSerializer
import ai.blamely.persistence.BlamelyRepoPaths
import ai.blamely.report.ReportYaml
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import java.io.File

class GenerateReportAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val blameService = project.getService(BlameMapService::class.java) ?: return
        val traceService = project.getService(TraceStoreService::class.java) ?: return
        val yaml = ReportYaml.generateAndPersistDetector(project, blameService.blameMap, traceService.traceStore, ideName = "IntelliJ")
        val dir = GitUtils.getBlamelyDir(project) ?: run {
            notify(project, "Not a git repository")
            return
        }
        dir.mkdirs()
        File(dir, "report.yml").writeText(yaml)
        // Per-branch copy: `.git/blamely/<sanitized-branch>/report.yml` matches the new
        // VS Code layout so each branch can keep its own latest report alongside the
        // session lifecycle folders.
        val branch = GitUtils.getBranch(project)
        val branchReport = BlamelyRepoPaths.reportFile(File(GitUtils.getGitDir(project) ?: dir.parent), branch)
        try {
            branchReport.parentFile?.mkdirs()
            branchReport.writeText(yaml)
        } catch (_: Exception) {}

        BlameSerializer.loadAll(project).forEach { (path, entries) ->
            blameService.blameMap.setFileBlame(path, entries)
        }
        blameService.blameMap.getTrackedFiles().forEach { path ->
            BlameSerializer.save(project, path, blameService.blameMap.getBlame(path))
        }
        notify(project, "Report generated at .git/blamely/report.yml and .git/blamely/${BlamelyRepoPaths.safeBranchName(branch)}/report.yml")
    }

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Blamely")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }
}
