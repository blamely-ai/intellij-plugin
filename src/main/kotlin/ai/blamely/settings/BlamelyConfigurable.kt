package ai.blamely.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

class BlamelyConfigurable : Configurable {

    private var panel: JPanel? = null
    private var showLineIconsCheckBox: JBCheckBox? = null
    private var aiToolCombo: ComboBox<String>? = null

    override fun getDisplayName(): String = "Blamely"

    override fun createComponent(): JComponent {
        val settings = BlamelySettings.getInstance()
        val lineIconsCb = JBCheckBox(
            "Show line icons in gutter (AI / Human from oobeya-cli runtime data)",
            settings.showGutterLineIcons
        )
        showLineIconsCheckBox = lineIconsCb

        val combo = ComboBox(arrayOf("auto", "copilot", "cursor"))
        combo.selectedItem = settings.aiTool.ifEmpty { "auto" }
        aiToolCombo = combo

        panel = FormBuilder.createFormBuilder()
            .addComponent(lineIconsCb)
            .addLabeledComponent("AI tool for detected edits:", combo)
            .addComponent(
                JBLabel(
                    "<html>Which tool to credit for inline completions and chat applies this " +
                        "plugin detects. <b>auto</b> infers from the installed plugin (GitHub " +
                        "Copilot → copilot). Set explicitly when using GitHub Copilot so edits " +
                        "aren't mislabelled. Copilot and Cursor are tracked independently.</html>"
                )
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .apply { border = JBUI.Borders.empty(10, 20) }
        return panel!!
    }

    override fun isModified(): Boolean {
        val s = BlamelySettings.getInstance()
        val lineIcons = showLineIconsCheckBox
        val combo = aiToolCombo
        if (lineIcons != null && lineIcons.isSelected != s.showGutterLineIcons) return true
        if (combo != null && (combo.selectedItem as? String ?: "auto") != s.aiTool) return true
        return false
    }

    override fun apply() {
        val s = BlamelySettings.getInstance()
        showLineIconsCheckBox?.let { s.showGutterLineIcons = it.isSelected }
        aiToolCombo?.let { s.aiTool = (it.selectedItem as? String) ?: "auto" }
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            project.getService(ai.blamely.ui.BlameDecorations::class.java)?.refresh()
        }
    }

    override fun reset() {
        val s = BlamelySettings.getInstance()
        showLineIconsCheckBox?.isSelected = s.showGutterLineIcons
        aiToolCombo?.selectedItem = s.aiTool.ifEmpty { "auto" }
    }
}
