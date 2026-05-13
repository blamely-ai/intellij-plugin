package ai.blamely.actions

import ai.blamely.git.GitUtils
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
/**
 * Pushes refs/notes/blamely to origin so the remote has Blamely blame notes.
 * Use this after "Push" (e.g. VCS → Push) so the remote gets the notes too.
 */
class PushNotesToRemoteAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repoRoot = GitUtils.getRepoRoot(project)
        if (repoRoot == null) {
            Messages.showErrorDialog(project, "Not a git repository (or could not resolve repo root).", "Blamely")
            return
        }
        if (GitUtils.pushGitNotes(repoRoot)) {
            Messages.showInfoMessage(project, "Pushed refs/notes/blamely to origin.", "Blamely")
        } else {
            Messages.showWarningDialog(
                project,
                "Push of refs/notes/blamely failed or skipped (no remote, or no notes). Run in terminal: git push origin refs/notes/blamely",
                "Blamely"
            )
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
