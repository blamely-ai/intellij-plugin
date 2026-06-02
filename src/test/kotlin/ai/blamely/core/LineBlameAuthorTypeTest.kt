package ai.blamely.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LineBlameAuthorTypeTest {

    @Test
    fun `only completion cli and chat gen types are AI`() {
        assertTrue(LineBlame.isAiInteractionType("completion"))
        assertTrue(LineBlame.isAiInteractionType("cli"))
        assertTrue(LineBlame.isAiInteractionType("chat"))
        // Case / whitespace tolerant.
        assertTrue(LineBlame.isAiInteractionType(" Completion "))

        assertFalse(LineBlame.isAiInteractionType("unknown"))
        assertFalse(LineBlame.isAiInteractionType("manual"))
        assertFalse(LineBlame.isAiInteractionType(""))
        assertFalse(LineBlame.isAiInteractionType(null))
    }

    @Test
    fun `AI gen type renders as AI regardless of char counts`() {
        val entry = LineBlame(
            lineNumber = 1,
            authorType = LineBlame.AuthorType.HUMAN,
            timestamp = "t",
            interactionType = "chat",
            aiChars = 0,
            humanChars = 0,
        )
        assertEquals(LineBlame.AuthorType.AI, entry.effectiveAuthorType())
    }

    @Test
    fun `non AI gen type renders as Human even when human chars present`() {
        val entry = LineBlame(
            lineNumber = 1,
            authorType = LineBlame.AuthorType.HUMAN,
            timestamp = "t",
            interactionType = "unknown",
            aiChars = 0,
            humanChars = 12,
        )
        assertEquals(LineBlame.AuthorType.HUMAN, entry.effectiveAuthorType())
    }
}
