package ai.blamely.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AiContextExtractorTest {

    @Test
    fun `sanitizeModelForReport returns null for null or blank`() {
        assertNull(AiContextExtractor.sanitizeModelForReport(null))
        assertNull(AiContextExtractor.sanitizeModelForReport(""))
        assertNull(AiContextExtractor.sanitizeModelForReport("   "))
    }

    @Test
    fun `sanitizeModelForReport returns null for package-like names`() {
        assertNull(AiContextExtractor.sanitizeModelForReport("com.github.copilot"))
        assertNull(AiContextExtractor.sanitizeModelForReport("org.jetbrains.ai"))
    }

    @Test
    fun `sanitizeModelForReport returns trimmed value for valid model names`() {
        assertEquals("GPT-4", AiContextExtractor.sanitizeModelForReport("GPT-4"))
        assertEquals("GPT-5.3-Codex", AiContextExtractor.sanitizeModelForReport("  GPT-5.3-Codex  "))
        assertEquals("Claude Sonnet 4.5", AiContextExtractor.sanitizeModelForReport("Claude Sonnet 4.5"))
    }

    @Test
    fun `resolveProviderName returns unknown for null`() {
        assertEquals("unknown", AiContextExtractor.resolveProviderName(null))
    }

    @Test
    fun `resolveProviderName maps known packages to provider names`() {
        assertEquals("github-copilot", AiContextExtractor.resolveProviderName("com.github.copilot"))
        assertEquals("github-copilot", AiContextExtractor.resolveProviderName("com.github.copilot.chat.CopilotChatService"))
        assertEquals("codeium", AiContextExtractor.resolveProviderName("com.codeium"))
        assertEquals("cursor", AiContextExtractor.resolveProviderName("com.cursor"))
    }

    @Test
    fun `resolveProviderName returns raw value for unknown package`() {
        assertEquals("some.unknown.Class", AiContextExtractor.resolveProviderName("some.unknown.Class"))
    }
}
