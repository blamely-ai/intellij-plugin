package ai.blamely.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings page: Settings → Tools → Blamely. Mirrors the VS Code extension's
 * `blamely.*` settings (see reference VS Code plugin under `reference-project/vscode-plugin`).
 */
class BlamelyConfigurable : Configurable {

    private var panel: JPanel? = null
    private var showLineIconsCheckBox: JBCheckBox? = null
    private var autoInstallHookCheckBox: JBCheckBox? = null
    private var reportOnSaveCheckBox: JBCheckBox? = null
    private var suggestionTimeoutField: JBTextField? = null
    private var excludePatternsArea: JBTextArea? = null
    private var additionalExcludePatternsArea: JBTextArea? = null

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

        val excludeTa = JBTextArea(settings.getExcludePatterns().joinToString("\n")).apply {
            rows = 10
            lineWrap = true
            wrapStyleWord = true
        }
        val excludeScroll = JBScrollPane(excludeTa).apply {
            preferredSize = Dimension(480, 160)
            border = JBUI.Borders.empty()
        }

        val additionalTa = JBTextArea(settings.getAdditionalExcludePatterns().joinToString("\n")).apply {
            rows = 4
            lineWrap = true
            wrapStyleWord = true
        }
        val additionalScroll = JBScrollPane(additionalTa).apply {
            preferredSize = Dimension(480, 72)
            border = JBUI.Borders.empty()
        }

        showLineIconsCheckBox = lineIconsCb
        autoInstallHookCheckBox = hookCb
        reportOnSaveCheckBox = reportCb
        suggestionTimeoutField = timeoutField
        excludePatternsArea = excludeTa
        additionalExcludePatternsArea = additionalTa

        panel = FormBuilder.createFormBuilder()
            .addComponent(lineIconsCb)
            .addComponent(hookCb)
            .addComponent(reportCb)
            .addLabeledComponent(JBLabel("AI suggestion timeout (ms):"), timeoutField, 1, false)
            .addSeparator()
            .addLabeledComponent(
                JBLabel(
                    "Exclude path patterns (substring match on project-relative paths, one per line). " +
                        "Merged with additional patterns below — same as VS Code blamely.excludePatterns + blamely.additionalExcludePatterns."
                ),
                excludeScroll,
                true
            )
            .addLabeledComponent(
                JBLabel("Additional exclude patterns (e.g. .snap):"),
                additionalScroll,
                true
            )
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
        val excludeArea = excludePatternsArea ?: return false
        val additionalArea = additionalExcludePatternsArea ?: return false
        val timeoutVal = timeoutField.text.trim().toIntOrNull() ?: s.suggestionTimeoutMs
        return lineIcons.isSelected != s.showGutterLineIcons
            || hookCb.isSelected != s.autoInstallHook
            || reportCb.isSelected != s.reportOnSave
            || timeoutVal != s.suggestionTimeoutMs
            || parsePatternLines(excludeArea.text) != s.getExcludePatterns()
            || parsePatternLines(additionalArea.text) != s.getAdditionalExcludePatterns()
    }

    override fun apply() {
        val s = BlamelySettings.getInstance()
        showLineIconsCheckBox?.let { s.showGutterLineIcons = it.isSelected }
        autoInstallHookCheckBox?.let { s.autoInstallHook = it.isSelected }
        reportOnSaveCheckBox?.let { s.reportOnSave = it.isSelected }
        suggestionTimeoutField?.text?.trim()?.toIntOrNull()?.let { s.suggestionTimeoutMs = it.coerceIn(1_000, 600_000) }
        excludePatternsArea?.let { s.setExcludePatterns(parsePatternLines(it.text)) }
        additionalExcludePatternsArea?.let { s.setAdditionalExcludePatterns(parsePatternLines(it.text)) }
        restartDaemonForLineIcons()
    }

    override fun reset() {
        val s = BlamelySettings.getInstance()
        showLineIconsCheckBox?.isSelected = s.showGutterLineIcons
        autoInstallHookCheckBox?.isSelected = s.autoInstallHook
        reportOnSaveCheckBox?.isSelected = s.reportOnSave
        suggestionTimeoutField?.text = s.suggestionTimeoutMs.toString()
        excludePatternsArea?.text = s.getExcludePatterns().joinToString("\n")
        additionalExcludePatternsArea?.text = s.getAdditionalExcludePatterns().joinToString("\n")
    }

    private fun parsePatternLines(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    private fun restartDaemonForLineIcons() {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            project.getService(ai.blamely.ui.BlameDecorations::class.java)?.refresh()
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }
}
