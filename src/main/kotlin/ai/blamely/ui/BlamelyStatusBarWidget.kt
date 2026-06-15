package ai.blamely.ui

import ai.blamely.core.BlameMapService
import ai.blamely.core.BlameUpdateListener
import ai.blamely.git.GitUtils
import ai.blamely.utils.BlankLines
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import java.io.File

/**
 * Status bar widget showing AI / Human by lines and percentage for the ACTIVE file.
 * Format: 🤖 AI: 1 ≡ 20% | 👤 Human: 2 ≡ 80%
 *
 * getText() MUST be cheap — the platform calls it on the EDT. Computing the
 * active-file summary needs a git repo-root lookup (which can run `git rev-parse`
 * and take a read lock) plus file I/O; doing that in getText() blocked the EDT and
 * could freeze the IDE while VCS held its locks. So the value is computed on a
 * pooled thread and cached, and getText() just returns the cached string.
 */
class BlamelyStatusBarWidget(project: Project) : EditorBasedWidget(project), StatusBarWidget.TextPresentation {

    private val iconLines = "≡"
    private val emptyText = "🤖 AI: 0 $iconLines 0% | 👤 Human: 0 $iconLines 0%"

    @Volatile private var cachedText: String = emptyText

    override fun ID(): String = WIDGET_ID
    override fun getPresentation(): StatusBarWidget.TextPresentation = this

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)
        // Recompute when the user switches editors (the count is per active file)
        // or when attribution data changes. EditorBasedWidget is Disposable, so
        // this connection is torn down with the widget.
        val conn = project.messageBus.connect(this)
        conn.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = scheduleRefresh()
        })
        conn.subscribe(BlameUpdateListener.TOPIC, object : BlameUpdateListener {
            override fun blameUpdated() = scheduleRefresh()
        })
        scheduleRefresh()
    }

    override fun getText(): String = cachedText

    /** Capture the active file on the EDT (editor access is EDT-only), do the git +
     *  blame work on a pooled thread, then repaint. Never blocks the EDT. */
    private fun scheduleRefresh() {
        val app = ApplicationManager.getApplication()
        app.invokeLater {
            if (project.isDisposed) return@invokeLater
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val filePath = editor?.let { FileDocumentManager.getInstance().getFile(it.document)?.path }
            app.executeOnPooledThread {
                if (project.isDisposed) return@executeOnPooledThread
                val next = renderFor(filePath)
                if (next != cachedText) {
                    cachedText = next
                    app.invokeLater {
                        if (!project.isDisposed) myStatusBar?.updateWidget(WIDGET_ID)
                    }
                }
            }
        }
    }

    /** Off-EDT: resolve the file's repo-relative path and tally its blame. Blank
     *  lines are read from disk (close enough to the gutter's live-document check
     *  without touching the editor off the EDT). */
    private fun renderFor(filePath: String?): String {
        if (filePath == null) return emptyText
        val blameService = project.getService(BlameMapService::class.java) ?: return emptyText
        val repoRoot = GitUtils.getRepoRoot(project) ?: project.basePath ?: return emptyText
        val rel = GitUtils.toRepoRelativePath(repoRoot, filePath) ?: return emptyText

        val lines = try { File(filePath).readText().split('\n') } catch (_: Exception) { null }
        val isBlank: (Int) -> Boolean = if (lines == null) {
            { false }
        } else {
            { ln -> val i = ln - 1; i in lines.indices && BlankLines.isBlankLine(lines[i]) }
        }
        val summary = blameService.blameMap.getSummaryForFile(rel, isBlank)
        val total = summary.aiLines + summary.humanLines
        if (total == 0) return emptyText
        val aiPercent = "%.0f".format((summary.aiLines.toDouble() / total) * 100)
        val humanPercent = "%.0f".format((summary.humanLines.toDouble() / total) * 100)
        return "🤖 AI: ${summary.aiLines} $iconLines $aiPercent% | 👤 Human: ${summary.humanLines} $iconLines $humanPercent%"
    }

    override fun getTooltipText(): String {
        val daemon = project.getService(ai.blamely.cli.CliDataService::class.java)?.daemonStatus
        val hint = when {
            daemon?.running == true -> "blamely daemon :${daemon.port}"
            daemon?.port != null -> "daemon offline"
            else -> "run blamely daemon"
        }
        return "Blamely — $hint. Click for Changes."
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer { _ ->
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Blamely")?.show()
    }

    override fun getAlignment(): Float = 0.5f

    companion object {
        const val WIDGET_ID = "Blamely.BlameStatus"
    }
}
