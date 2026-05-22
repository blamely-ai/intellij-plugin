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

/**
 * Attaches (or overwrites) the Blamely git note for the current HEAD commit.
 * Use when you see "no note found" — e.g. the note was never added or the listener didn't run.
 * Note is stored under refs/notes/blamely.
 */
class AttachGitNoteAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repoRoot = GitUtils.getRepoRoot(project) ?: project.basePath
        if (repoRoot == null || !GitUtils.isGitRepo(project)) {
            notify(project, "Not a git repository (or could not resolve repo root)", NotificationType.ERROR)
            return
        }
        val sha = GitUtils.getLatestCommitSha(project) ?: run {
            notify(project, "Could not get current commit (HEAD)", NotificationType.ERROR)
            return
        }
        val blameService = project.getService(BlameMapService::class.java) ?: return
        val traceService = project.getService(TraceStoreService::class.java) ?: return
        val blameMap = blameService.blameMap
        val traceStore = traceService.traceStore

        val changedRepoRelative = GitUtils.getFilesChangedInCommit(repoRoot, sha).toSet()
        val changedProjectRelative = GitUtils.repoRelativeToProjectRelative(repoRoot, project.basePath, changedRepoRelative)
        blameMap.setCommitShaForFiles(sha, changedProjectRelative)
        val normalizedChanged = changedProjectRelative.map { it.replace('\\', '/') }.toSet()
        val entireBlame = mutableMapOf<String, List<ai.blamely.core.LineBlame>>()
        for (file in blameMap.getTrackedFiles()) {
            if (file.replace('\\', '/') !in normalizedChanged) continue
            val entries = blameMap.getBlame(file).filter { it.commitSha == sha }
            if (entries.isNotEmpty()) entireBlame[file] = entries
        }
        val ts = java.time.Instant.now().toString()
        for (repoRel in changedRepoRelative) {
            val projectRelSet = GitUtils.repoRelativeToProjectRelative(repoRoot, project.basePath, listOf(repoRel))
            val projectRel = projectRelSet.singleOrNull() ?: continue
            val norm = projectRel.replace('\\', '/')
            if (norm in normalizedChanged && !entireBlame.containsKey(norm)) {
                val addedLines = GitUtils.getAddedLineNumbersInCommit(repoRoot, sha, repoRel)
                if (addedLines.isNotEmpty()) {
                    entireBlame[projectRel] = addedLines.map { line ->
                        ai.blamely.core.LineBlame(
                            lineNumber = line,
                            authorType = ai.blamely.core.LineBlame.AuthorType.HUMAN,
                            timestamp = ts,
                            commitSha = sha,
                            aiChars = 0,
                            humanChars = 10
                        )
                    }
                }
            }
        }
        val yamlReport = if (entireBlame.isNotEmpty()) {
            ReportYaml.generateFromBlameSnapshot(project, entireBlame, traceStore, sha, "IntelliJ")
        } else {
            ReportYaml.generate(project, blameMap, traceStore, sha, "IntelliJ")
        }
        val noteContent = yamlReport.trimEnd()

        val result = GitUtils.addGitNoteWithResult(repoRoot, sha, noteContent)
        if (result.ok) {
            GitUtils.pushGitNotes(repoRoot)
            notify(project, "${result.describe(sha)} Verify: git notes --ref=blamely show HEAD")
        } else {
            notify(project, result.describe(sha), NotificationType.WARNING)
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
