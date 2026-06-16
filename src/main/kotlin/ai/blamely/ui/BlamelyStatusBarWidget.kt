package ai.blamely.ui

import ai.blamely.core.BlameMapService
import ai.blamely.core.BlameUpdateListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

/**
 * Status bar widget showing AI / Human by lines and percentage for the WHOLE
 * SESSION — every changed file tracked this session, not just the active editor.
 * Format: 🤖 AI: 1 ≡ 20% | 👤 Human: 2 ≡ 80%
 *
 * getText() MUST be cheap — the platform calls it on the EDT. The summary is
 * computed on a pooled thread and cached; getText() just returns the cached string.
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
        // Recompute when attribution data changes. The count is session-wide, so
        // switching editors doesn't change it — no FileEditorManager listener needed.
        // EditorBasedWidget is Disposable, so this connection is torn down with it.
        val conn = project.messageBus.connect(this)
        conn.subscribe(BlameUpdateListener.TOPIC, object : BlameUpdateListener {
            override fun blameUpdated() = scheduleRefresh()
        })
        scheduleRefresh()
    }

    override fun getText(): String = cachedText

    /** Compute the session-wide tally on a pooled thread (keeps getText() cheap),
     *  then repaint only if it changed. Never blocks the EDT. */
    private fun scheduleRefresh() {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val next = renderSession()
            if (next != cachedText) {
                cachedText = next
                app.invokeLater {
                    if (!project.isDisposed) myStatusBar?.updateWidget(WIDGET_ID)
                }
            }
        }
    }

    /** Off-EDT: tally AI/Human across ALL changed files tracked this session. */
    private fun renderSession(): String {
        val blameService = project.getService(BlameMapService::class.java) ?: return emptyText
        val summary = blameService.blameMap.getSummary()
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
