package ai.blamely.authorship

import ai.blamely.core.LineBlame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// workingLogToLineBlame is the gutter's data transform (working log → per-line
// LineBlame). Mirrors the VS Code WorkingLogBlame.test.ts; a bug here paints wrong
// icons.
class WorkingLogBlameTest {
    @Test
    fun aiRangeExpandsToPerLineAiEntries() {
        val wl = WorkingLogJson(
            file = "a.kt",
            lines = listOf(WlLine(start = 3, end = 5, author = "ai", tool = "claude", model = "opus", genType = "chat")),
        )
        val out = workingLogToLineBlame(wl)
        assertEquals(3, out.size)
        out.forEachIndexed { i, e ->
            assertEquals(3 + i, e.lineNumber)
            assertEquals(LineBlame.AuthorType.AI, e.authorType)
            assertEquals("claude", e.provider)
            assertEquals("opus", e.model)
            assertEquals("chat", e.interactionType)
            assertEquals(1, e.aiChars)
            assertEquals(0, e.humanChars)
            assertEquals(LineBlame.ChangeType.ADD, e.changeType)
        }
    }

    @Test
    fun humanRangeExpandsToHumanEntriesWithNoAiMetadata() {
        val out = workingLogToLineBlame(WorkingLogJson(lines = listOf(WlLine(start = 1, end = 2, author = "human"))))
        assertEquals(2, out.size)
        for (e in out) {
            assertEquals(LineBlame.AuthorType.HUMAN, e.authorType)
            assertNull(e.provider)
            assertNull(e.model)
            assertEquals(0, e.aiChars)
            assertEquals(1, e.humanChars)
        }
    }

    @Test
    fun mixedRangesAndEmpty() {
        assertTrue(workingLogToLineBlame(WorkingLogJson()).isEmpty())
        assertTrue(workingLogToLineBlame(WorkingLogJson(lines = emptyList())).isEmpty())
        val mixed = workingLogToLineBlame(
            WorkingLogJson(
                lines = listOf(
                    WlLine(start = 1, end = 1, author = "human"),
                    WlLine(start = 2, end = 2, author = "ai", tool = "codex"),
                ),
            ),
        )
        assertEquals(2, mixed.size)
        assertEquals(LineBlame.AuthorType.HUMAN, mixed[0].authorType)
        assertEquals(LineBlame.AuthorType.AI, mixed[1].authorType)
        assertEquals("codex", mixed[1].provider)
    }
}
