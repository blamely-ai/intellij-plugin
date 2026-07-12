package ai.blamely.completion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for the source-derived tool resolution. Outside a running IDE the
 * settings service and ActionManager are unavailable — resolveTool treats that
 * as aiTool=auto with no Copilot plugin, which is exactly the environment where
 * the fallback behavior matters.
 */
class ResolveToolTest {

    @Test
    fun `signal source wins in auto mode`() {
        assertEquals("copilot", resolveTool("copilot"))
        assertEquals("gemini", resolveTool("gemini"))
    }

    @Test
    fun `default is copilot - never cursor on a JetBrains host`() {
        // Cursor is a VS Code fork; it can never host this plugin, so an
        // unknown-source AI edit must not be labeled cursor.
        assertEquals("copilot", resolveTool(null))
        assertEquals("copilot", resolveTool())
    }

    @Test
    fun `sourceToolFromActionId maps known families and rejects the rest`() {
        assertEquals("copilot", sourceToolFromActionId("copilot.diffBlock.accept"))
        assertEquals("copilot", sourceToolFromActionId("com.github.copilot.chat.applyInEditor"))
        assertEquals("gemini", sourceToolFromActionId("Gemini.Chat.ApplyCode"))
        assertNull(sourceToolFromActionId("AIAssistant.Editor.ApplySuggestion"))
        assertNull(sourceToolFromActionId("InlineCompletion.Insert"))
    }
}
