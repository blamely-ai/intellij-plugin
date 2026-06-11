package ai.blamely.ui

import ai.blamely.core.LineBlame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlameDecorationsTooltipTest {

    @Test
    fun `formatBlameChangedDate returns Unknown when blank`() {
        assertEquals("Unknown", BlameDecorations.formatBlameChangedDate(""))
        assertEquals("Unknown", BlameDecorations.formatBlameChangedDate("   "))
    }

    @Test
    fun `formatBlameChangedDate falls back to raw when not ISO instant`() {
        assertEquals("not-a-date", BlameDecorations.formatBlameChangedDate("not-a-date"))
    }

    @Test
    fun `formatBlameChangedDate formats ISO instant`() {
        val formatted = BlameDecorations.formatBlameChangedDate("2026-05-01T12:30:00Z")
        assertTrue(formatted.isNotBlank())
        assertTrue(formatted.contains("2026"))
    }

    @Test
    fun `blameGutterTooltipText human shows Author and Changed`() {
        val entry = LineBlame(
            lineNumber = 1,
            authorType = LineBlame.AuthorType.HUMAN,
            timestamp = "2026-05-01T12:00:00Z",
            aiChars = 0,
            humanChars = 5
        )
        val text = BlameDecorations.blameGutterTooltipText(entry, LineBlame.AuthorType.HUMAN)
        assertTrue(text.contains("Author: Human"), text)
        assertTrue(text.contains("Change Date:"), text)
    }

    @Test
    fun `blameGutterTooltipText ai shows Author Assistant Changed`() {
        val entry = LineBlame(
            lineNumber = 2,
            authorType = LineBlame.AuthorType.AI,
            provider = "copilot",
            timestamp = "2026-05-01T15:00:00Z",
            model = "gpt-4",
            interactionType = "completion",
            aiChars = 10,
            humanChars = 0
        )
        val text = BlameDecorations.blameGutterTooltipText(entry, LineBlame.AuthorType.AI)
        assertTrue(text.contains("Author: AI"), text)
        assertTrue(text.contains("Change Date:"), text)
        assertTrue(text.contains("Tool: Copilot"), text)
        assertTrue(text.contains("Model: gpt-4"), text)
        assertTrue(!text.contains("Interaction:"), "AI gutter tooltip should not show interaction/source")
    }

    @Test
    fun `toolDisplayName capitalizes raw provider id`() {
        assertEquals("Codex", BlameDecorations.toolDisplayName("codex"))
        assertEquals("Copilot", BlameDecorations.toolDisplayName("copilot"))
        assertEquals("Claude", BlameDecorations.toolDisplayName("CLAUDE"))
    }
}
