package ai.blamely.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Central icon holder for Blamely. Loads from plugin resources with fallbacks so icons always resolve.
 * Gutter icons use SVG (parity with VS Code `BlameDecorations.ts`); other UI still uses bundled PNGs where referenced.
 */
object BlamelyIcons {
    private fun load(path: String, fallback: Icon): Icon = try {
        IconLoader.getIcon(path, BlamelyIcons::class.java)
    } catch (_: Throwable) {
        fallback
    }

    /** AI attribution (Blamely logo). Used in gutter and line markers. */
    @JvmField
    val Brain = load("/icons/Blamely13.png", AllIcons.Nodes.MethodReference)

    /** Human attribution. Used in gutter. */
    @JvmField
    val Human = AllIcons.General.User

    /** AI gutter — same SVG as VS Code `BlameDecorations` (brain outline, #4d9de0). */
    @JvmField
    val GutterBrain = load("/icons/gutter-ai.svg", Brain)

    /** Human gutter — same SVG as VS Code (person outline, #56a064). */
    @JvmField
    val GutterHuman = load("/icons/gutter-human.svg", Human)

    /** Neutral "detecting authorship…" gutter — three-quarter amber ring, same as
     *  VS Code's GUTTER_PENDING_SVG. Shown on AI-likely lines awaiting attribution. */
    @JvmField
    val GutterDetecting = load("/icons/gutter-detecting.svg", AllIcons.General.BalloonInformation)
}
