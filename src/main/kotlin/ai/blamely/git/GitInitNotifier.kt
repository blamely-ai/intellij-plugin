package ai.blamely.git

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * Notify the user when the opened project isn't a Git repository and offer to
 * initialize one. Blamely attributes lines via Git, so it's inert until a repo
 * exists. Shown once per project (dismissible) and honored across restarts via
 * a persisted "Don't Ask Again" flag.
 *
 * Mirror of the VS Code plugin's `maybePromptGitInit` (extension.ts). Keep both
 * in sync — the plugins are parallel implementations.
 */
object GitInitNotifier {

    private const val DISMISS_KEY = "blamely.gitInitDismissed"

    fun maybePrompt(project: Project, onInitialized: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed || project.isDefault) return@executeOnPooledThread
            // Honor the "never prompt" setting (parity with VS Code's blamely.promptGitInit).
            if (!ai.blamely.settings.BlamelySettings.getInstance().promptGitInit) return@executeOnPooledThread
            val basePath = project.basePath ?: return@executeOnPooledThread
            if (PropertiesComponent.getInstance(project).getBoolean(DISMISS_KEY, false)) {
                return@executeOnPooledThread
            }
            // getRepoRoot falls back to basePath even outside a repo, so check
            // directly: rev-parse fails (→ null) when there's no working tree.
            if (GitUtils.run(basePath, "rev-parse", "--is-inside-work-tree") == "true") {
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) showNotification(project, basePath, onInitialized)
            }
        }
    }

    private fun showNotification(project: Project, basePath: String, onInitialized: () -> Unit) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Blamely")
        Notification(
            group.displayId,
            "Blamely: not a Git repository",
            "\"${project.name}\" isn't a Git repository yet. Blamely needs Git to track who wrote each line.",
            NotificationType.INFORMATION
        ).apply {
            addAction(NotificationAction.createSimple("Initialize Git") {
                initRepo(project, basePath, onInitialized)
                expire()
            })
            addAction(NotificationAction.createSimple("Don't Ask Again") {
                PropertiesComponent.getInstance(project).setValue(DISMISS_KEY, true)
                expire()
            })
        }.notify(project)
    }

    private fun initRepo(project: Project, basePath: String, onInitialized: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val ok = GitUtils.run(basePath, "init") != null
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val group = NotificationGroupManager.getInstance().getNotificationGroup("Blamely")
                if (ok) {
                    GitUtils.clearRepoRootCache()
                    Notification(
                        group.displayId,
                        "Blamely",
                        "Initialized a Git repository in \"${project.name}\".",
                        NotificationType.INFORMATION
                    ).notify(project)
                    onInitialized()
                } else {
                    Notification(
                        group.displayId,
                        "Blamely",
                        "Failed to initialize a Git repository in \"${project.name}\". Is Git installed and on PATH?",
                        NotificationType.ERROR
                    ).notify(project)
                }
            }
        }
    }
}
