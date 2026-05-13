package ai.blamely.ui

import ai.blamely.core.BlameMapService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

/**
 * Status bar widget showing AI / Human by characters, lines, and percentage.
 * Uses icons: ⓒ = chars, ≡ = lines. Format: 🤖 AI: 20 ⓒ 1 ≡ 20% | 👤 Human: 35 ⓒ 2 ≡ 80%
 */
class BlamelyStatusBarWidget(project: Project) : EditorBasedWidget(project), StatusBarWidget.TextPresentation {

    private val iconChars = "ⓒ"
    private val iconLines = "≡"

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.TextPresentation = this

    override fun getText(): String {
        val blameService = project.getService(BlameMapService::class.java)
            ?: return "🤖 AI: 0 $iconChars 0 $iconLines 0% | 👤 Human: 0 $iconChars 0 $iconLines 0%"
        val summary = blameService.blameMap.getSummary()
        val totalChars = summary.aiChars + summary.humanChars
        val totalLines = summary.totalLines
        return if (totalChars == 0 && totalLines == 0) {
            "🤖 AI: 0 $iconChars 0 $iconLines 0% | 👤 Human: 0 $iconChars 0 $iconLines 0%"
        } else {
            val totalForPercent = totalChars.coerceAtLeast(1)
            val aiPercent = "%.0f".format((summary.aiChars.toDouble() / totalForPercent) * 100)
            val humanPercent = "%.0f".format((summary.humanChars.toDouble() / totalForPercent) * 100)
            "🤖 AI: ${summary.aiChars} $iconChars ${summary.aiLines} $iconLines $aiPercent% | 👤 Human: ${summary.humanChars} $iconChars ${summary.humanLines} $iconLines $humanPercent%"
        }
    }

    override fun getTooltipText(): String = "Blamely — ⓒ chars, ≡ lines. Click to view details."

    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer { _ ->
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Blamely")?.show()
    }

    override fun getAlignment(): Float = 0.5f

    companion object {
        const val WIDGET_ID = "Blamely.BlameStatus"
    }
}
