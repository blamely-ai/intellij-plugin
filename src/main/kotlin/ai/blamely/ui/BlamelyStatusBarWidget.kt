package ai.blamely.ui

import ai.blamely.core.BlameMap
import ai.blamely.core.BlameMapService
import ai.blamely.git.GitUtils
import ai.blamely.utils.BlankLines
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

/**
 * Status bar widget showing AI / Human by lines and percentage.
 * Format: 🤖 AI: 1 ≡ 20% | 👤 Human: 2 ≡ 80%
 */
class BlamelyStatusBarWidget(project: Project) : EditorBasedWidget(project), StatusBarWidget.TextPresentation {

    private val iconLines = "≡"

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.TextPresentation = this

    override fun getText(): String {
        val blameService = project.getService(BlameMapService::class.java)
            ?: return "🤖 AI: 0 $iconLines 0% | 👤 Human: 0 $iconLines 0%"
        // Count the ACTIVE FILE only (with blank lines excluded) so the bar equals
        // the gutter icons in front of the user. Falls back to the workspace total
        // only when no file editor is focused / the file isn't in this repo.
        val summary = activeFileSummary(blameService) ?: blameService.blameMap.getSummary()
        val totalLines = summary.aiLines + summary.humanLines
        return if (totalLines == 0) {
            "🤖 AI: 0 $iconLines 0% | 👤 Human: 0 $iconLines 0%"
        } else {
            val aiPercent = "%.0f".format((summary.aiLines.toDouble() / totalLines) * 100)
            val humanPercent = "%.0f".format((summary.humanLines.toDouble() / totalLines) * 100)
            "🤖 AI: ${summary.aiLines} $iconLines $aiPercent% | 👤 Human: ${summary.humanLines} $iconLines $humanPercent%"
        }
    }

    /** Summary scoped to the file in the active editor, blank lines excluded —
     *  resolved exactly like the gutter (repo-relative path + live blank check) so
     *  the two always agree. Null when no file editor is focused or the file isn't
     *  in this project's repo (caller then falls back to the workspace total). */
    private fun activeFileSummary(blameService: BlameMapService): BlameMap.Summary? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val doc = editor.document
        val file = FileDocumentManager.getInstance().getFile(doc) ?: return null
        val repoRoot = GitUtils.getRepoRoot(project) ?: project.basePath ?: return null
        val path = GitUtils.toRepoRelativePath(repoRoot, file.path) ?: return null
        return blameService.blameMap.getSummaryForFile(path) { line ->
            val idx = line - 1
            if (idx < 0 || idx >= doc.lineCount) return@getSummaryForFile false
            BlankLines.isBlankLine(doc.getText(TextRange(doc.getLineStartOffset(idx), doc.getLineEndOffset(idx))))
        }
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
