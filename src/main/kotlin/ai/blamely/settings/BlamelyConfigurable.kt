package ai.blamely.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

class BlamelyConfigurable : Configurable {

    private var panel: JPanel? = null
    private var showLineIconsCheckBox: JBCheckBox? = null

    override fun getDisplayName(): String = "Blamely"

    override fun createComponent(): JComponent {
        val settings = BlamelySettings.getInstance()
        val lineIconsCb = JBCheckBox(
            "Show line icons in gutter (AI / Human from oobeya-cli runtime data)",
            settings.showGutterLineIcons
        )
        showLineIconsCheckBox = lineIconsCb
        panel = FormBuilder.createFormBuilder()
            .addComponent(lineIconsCb)
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .apply { border = JBUI.Borders.empty(10, 20) }
        return panel!!
    }

    override fun isModified(): Boolean {
        val s = BlamelySettings.getInstance()
        val lineIcons = showLineIconsCheckBox ?: return false
        return lineIcons.isSelected != s.showGutterLineIcons
    }

    override fun apply() {
        val s = BlamelySettings.getInstance()
        showLineIconsCheckBox?.let { s.showGutterLineIcons = it.isSelected }
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            project.getService(ai.blamely.ui.BlameDecorations::class.java)?.refresh()
        }
    }

    override fun reset() {
        val s = BlamelySettings.getInstance()
        showLineIconsCheckBox?.isSelected = s.showGutterLineIcons
    }
}
