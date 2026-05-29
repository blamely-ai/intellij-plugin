package ai.blamely.ui

import ai.blamely.core.BlameMapService
import com.intellij.openapi.project.Project
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
        val summary = blameService.blameMap.getSummary()
        val totalLines = summary.aiLines + summary.humanLines
        return if (totalLines == 0) {
            "🤖 AI: 0 $iconLines 0% | 👤 Human: 0 $iconLines 0%"
        } else {
            val aiPercent = "%.0f".format((summary.aiLines.toDouble() / totalLines) * 100)
            val humanPercent = "%.0f".format((summary.humanLines.toDouble() / totalLines) * 100)
            "🤖 AI: ${summary.aiLines} $iconLines $aiPercent% | 👤 Human: ${summary.humanLines} $iconLines $humanPercent%"
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
