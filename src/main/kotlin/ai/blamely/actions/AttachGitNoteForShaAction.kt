package ai.blamely.actions

import ai.blamely.core.BlameMapService
import ai.blamely.core.TraceStoreService
import ai.blamely.git.GitUtils
import ai.blamely.persistence.BlameSerializer
import ai.blamely.report.ReportYaml
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.awt.GridLayout
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Attaches the Blamely git note for a specific commit SHA (e.g. when you see "no note found for object ...").
 * Lets you paste the full SHA and attach the note so that commit is no longer missing a note.
 */
class AttachGitNoteForShaAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repoRoot = GitUtils.getRepoRoot(project) ?: project.basePath
        if (repoRoot == null || !GitUtils.isGitRepo(project)) {
            notify(project, "Not a git repository (or could not resolve repo root)", NotificationType.ERROR)
            return
        }
        val headSha = GitUtils.getLatestCommitSha(project)
        val panel = JPanel(GridLayout(1, 2)).apply {
            add(JLabel("Commit SHA:"))
            add(JTextField(headSha ?: "", 42).also { it.name = "sha" })
        }
        val dialogResult = JOptionPane.showConfirmDialog(
            null,
            panel,
            "Attach Git Note for Commit",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )
        if (dialogResult != JOptionPane.OK_OPTION) return
        val sha = (panel.getComponent(1) as? JTextField)?.text?.trim() ?: return
        if (sha.length < 8) {
            notify(project, "Please enter a valid commit SHA (at least 8 characters)", NotificationType.ERROR)
            return
        }
        // Resolve to full SHA if short
        val fullSha = if (sha.length == 40) sha else GitUtils.run(repoRoot, "rev-parse", sha) ?: sha
        val blameService = project.getService(BlameMapService::class.java) ?: return
        val traceService = project.getService(TraceStoreService::class.java) ?: return
        val blameMap = blameService.blameMap
        val traceStore = traceService.traceStore
        val changedRepoRelative = GitUtils.getFilesChangedInCommit(repoRoot, fullSha).toSet()
        val changedProjectRelative = GitUtils.repoRelativeToProjectRelative(repoRoot, project.basePath, changedRepoRelative)
        blameMap.setCommitShaForFiles(fullSha, changedProjectRelative)
        val normalizedChanged = changedProjectRelative.map { it.replace('\\', '/') }.toSet()
        val entireBlame = mutableMapOf<String, List<ai.blamely.core.LineBlame>>()
        for (file in blameMap.getTrackedFiles()) {
            if (file.replace('\\', '/') !in normalizedChanged) continue
            val entries = blameMap.getBlame(file).filter { it.commitSha == fullSha }
            if (entries.isNotEmpty()) entireBlame[file] = entries
        }
        val ts = java.time.Instant.now().toString()
        for (repoRel in changedRepoRelative) {
            val projectRelSet = GitUtils.repoRelativeToProjectRelative(repoRoot, project.basePath, listOf(repoRel))
            val projectRel = projectRelSet.singleOrNull() ?: continue
            val norm = projectRel.replace('\\', '/')
            if (norm in normalizedChanged && !entireBlame.containsKey(norm)) {
                val addedLines = GitUtils.getAddedLineNumbersInCommit(repoRoot, fullSha, repoRel)
                if (addedLines.isNotEmpty()) {
                    entireBlame[projectRel] = addedLines.map { line ->
                        ai.blamely.core.LineBlame(
                            lineNumber = line,
                            authorType = ai.blamely.core.LineBlame.AuthorType.HUMAN,
                            timestamp = ts,
                            commitSha = fullSha,
                            aiChars = 0,
                            humanChars = 10
                        )
                    }
                }
            }
        }
        val yamlReport = if (entireBlame.isNotEmpty()) {
            ReportYaml.generateFromBlameSnapshot(project, entireBlame, traceStore, fullSha, "IntelliJ")
        } else {
            ReportYaml.generate(project, blameMap, traceStore, fullSha, "IntelliJ")
        }
        val snapshotYaml = ReportYaml.blameSnapshotToYaml(entireBlame)
        val noteContent = "${yamlReport}blames:\n$snapshotYaml"
        val result = GitUtils.addGitNoteWithResult(repoRoot, fullSha, noteContent)
        if (result.ok) {
            GitUtils.pushGitNotes(repoRoot)
            notify(project, "${result.describe(fullSha)} Verify: git notes --ref=blamely show $fullSha")
        } else {
            notify(project, result.describe(fullSha), NotificationType.WARNING)
        }
        for (file in blameMap.getTrackedFiles()) {
            if (file.replace('\\', '/') in normalizedChanged) {
                BlameSerializer.save(project, file, blameMap.getBlame(file))
            }
        }
    }

    private fun notify(project: Project, message: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance().getNotificationGroup("Blamely")
            .createNotification(message, type)
            .notify(project)
    }
}
