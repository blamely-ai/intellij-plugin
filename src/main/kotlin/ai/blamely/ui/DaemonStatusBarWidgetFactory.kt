package ai.blamely.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class DaemonStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = DaemonStatusBar.WIDGET_ID
    override fun getDisplayName(): String = "Blamely Daemon Status"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = DaemonStatusBar(project)
}
