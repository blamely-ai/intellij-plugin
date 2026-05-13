package ai.blamely.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidgetFactory

/**
 * Factory for the Blamely status bar widget (AI / Human chars, lines, percentage).
 */
class BlamelyStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = BlamelyStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = "Blamely Attribution"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): BlamelyStatusBarWidget = BlamelyStatusBarWidget(project)
}
