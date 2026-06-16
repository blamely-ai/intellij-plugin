package ai.blamely

import ai.blamely.cli.CliDataService
import ai.blamely.git.GitUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Runs after dumb-mode/indexing finishes so [GitUtils.getRepoRoot] can use
 * git4idea and SQLite attribution loads reliably on IDE open.
 *
 * Implements [ProjectActivity] — IntelliJ 2026.1 (build 261) removed the old
 * StartupActivity API (it throws "Migrate … to ProjectActivity" and never runs).
 */
class BlamelyDeferredStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (project.isDefault || project.basePath == null) return
        GitUtils.clearRepoRootCache()
        val cliData = project.getService(CliDataService::class.java) ?: return
        cliData.refresh()
        // Extra pass after indexing — first refresh often runs before git4idea is ready.
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) cliData.refresh()
        }
    }
}
