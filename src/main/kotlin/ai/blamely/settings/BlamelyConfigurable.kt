package ai.blamely.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings page: Settings → Tools → Blamely. Mirrors the VS Code extension's
 * `blamely.*` settings (gutter decorations, auto-install hook, report on save,
 * suggestion timeout).
 */
class BlamelyConfigurable : Configurable {

    private var panel: JPanel? = null
    private var showLineIconsCheckBox: JBCheckBox? = null
    private var autoInstallHookCheckBox: JBCheckBox? = null
    private var reportOnSaveCheckBox: JBCheckBox? = null
    private var suggestionTimeoutField: JBTextField? = null

    override fun getDisplayName(): String = "Blamely"

    override fun createComponent(): JComponent {
        val settings = BlamelySettings.getInstance()
        val lineIconsCb = JBCheckBox(
            "Show line icons in gutter (AI / Human next to line numbers)",
            settings.showGutterLineIcons
        )
        val hookCb = JBCheckBox(
            "Auto-install Blamely git hooks on project open",
            settings.autoInstallHook
        )
        val reportCb = JBCheckBox(
            "Generate report.yml on document save",
            settings.reportOnSave
        )
        val timeoutField = JBTextField(settings.suggestionTimeoutMs.toString(), 8)

        showLineIconsCheckBox = lineIconsCb
        autoInstallHookCheckBox = hookCb
        reportOnSaveCheckBox = reportCb
        suggestionTimeoutField = timeoutField

        panel = FormBuilder.createFormBuilder()
            .addComponent(lineIconsCb)
            .addComponent(hookCb)
            .addComponent(reportCb)
            .addLabeledComponent(JBLabel("AI suggestion timeout (ms):"), timeoutField, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .apply { border = JBUI.Borders.empty(10, 20) }
        return panel!!
    }

    override fun isModified(): Boolean {
        val s = BlamelySettings.getInstance()
        val lineIcons = showLineIconsCheckBox ?: return false
        val hookCb = autoInstallHookCheckBox ?: return false
        val reportCb = reportOnSaveCheckBox ?: return false
        val timeoutField = suggestionTimeoutField ?: return false
        val timeoutVal = timeoutField.text.trim().toIntOrNull() ?: s.suggestionTimeoutMs
        return lineIcons.isSelected != s.showGutterLineIcons
            || hookCb.isSelected != s.autoInstallHook
            || reportCb.isSelected != s.reportOnSave
            || timeoutVal != s.suggestionTimeoutMs
    }

    override fun apply() {
        val s = BlamelySettings.getInstance()
        showLineIconsCheckBox?.let { s.showGutterLineIcons = it.isSelected }
        autoInstallHookCheckBox?.let { s.autoInstallHook = it.isSelected }
        reportOnSaveCheckBox?.let { s.reportOnSave = it.isSelected }
        suggestionTimeoutField?.text?.trim()?.toIntOrNull()?.let { s.suggestionTimeoutMs = it.coerceIn(1_000, 600_000) }
        restartDaemonForLineIcons()
    }

    override fun reset() {
        val s = BlamelySettings.getInstance()
        showLineIconsCheckBox?.isSelected = s.showGutterLineIcons
        autoInstallHookCheckBox?.isSelected = s.autoInstallHook
        reportOnSaveCheckBox?.isSelected = s.reportOnSave
        suggestionTimeoutField?.text = s.suggestionTimeoutMs.toString()
    }

    private fun restartDaemonForLineIcons() {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            project.getService(ai.blamely.ui.BlameDecorations::class.java)?.refresh()
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }
}
