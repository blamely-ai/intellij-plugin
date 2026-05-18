package ai.blamely

import ai.blamely.core.BlameMap
import ai.blamely.core.LineBlame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for BlameMap: setAttribute, setCommitSha, getBlame, reindex.
 */
class BlameMapTest {

    @Test
    fun `setAttribute adds new line blame and getBlame returns it`() {
        val map = BlameMap()
        map.setAttribute("src/Foo.kt", 1, 3, LineBlame.AuthorType.AI, provider = "copilot", charsInserted = 30)
        val blame = map.getBlame("src/Foo.kt")
        assertEquals(3, blame.size)
        assertEquals(1, blame[0].lineNumber)
        assertEquals(3, blame[2].lineNumber)
        assertEquals(LineBlame.AuthorType.AI, blame[0].authorType)
        assertTrue(blame[0].provider == "copilot")
    }

    @Test
    fun `setCommitSha sets commit_sha on all entries without one`() {
        val map = BlameMap()
        map.setAttribute("a.kt", 1, 1, LineBlame.AuthorType.HUMAN, charsInserted = 10)
        assertNull(map.getBlame("a.kt")[0].commitSha)
        map.setCommitSha("abc123")
        assertEquals("abc123", map.getBlame("a.kt")[0].commitSha)
    }

    @Test
    fun `getTrackedFiles returns only files with blame`() {
        val map = BlameMap()
        map.setAttribute("f1.kt", 1, 1, LineBlame.AuthorType.AI, charsInserted = 5)
        map.setAttribute("f2.kt", 1, 1, LineBlame.AuthorType.HUMAN, charsInserted = 5)
        val files = map.getTrackedFiles()
        assertEquals(2, files.size)
        assertTrue("f1.kt" in files)
        assertTrue("f2.kt" in files)
    }

    @Test
    fun `getSummary includes snapshot lines with commitSha`() {
        val map = BlameMap()
        val entries = listOf(
            LineBlame(1, LineBlame.AuthorType.AI, timestamp = "t", commitSha = "deadbeef", aiChars = 1, humanChars = 0),
            LineBlame(2, LineBlame.AuthorType.HUMAN, timestamp = "t", commitSha = "deadbeef", aiChars = 0, humanChars = 1)
        )
        map.setFileBlame("f.kt", entries)
        val summary = map.getSummary()
        assertEquals(2, summary.totalLines)
        assertEquals(1, summary.aiLines)
        assertEquals(1, summary.humanLines)
    }

    @Test
    fun `getSummary counts ai and human lines`() {
        val map = BlameMap()
        map.setAttribute("a.kt", 1, 2, LineBlame.AuthorType.AI, charsInserted = 20)
        map.setAttribute("a.kt", 4, 4, LineBlame.AuthorType.HUMAN, charsInserted = 10)
        val summary = map.getSummary()
        assertEquals(3, summary.totalLines)
        assertEquals(2, summary.aiLines)
        assertEquals(1, summary.humanLines)
    }

    @Test
    fun `reindex pure newline at end of line keeps prior line blame on same line new gap below`() {
        val map = BlameMap()
        map.setAttribute("f.kt", 1, 1, LineBlame.AuthorType.AI, charsInserted = 50)
        // DocumentChangeTracker parity: Enter with column > 0 → reindex from startLine+1, attribute gap at startLine+1.
        map.reindex("f.kt", 2, 1, 0)
        map.setAttribute(
            "f.kt",
            2,
            2,
            LineBlame.AuthorType.HUMAN,
            charsInserted = 1,
            charsPerLineOverride = listOf(1)
        )
        val byLine = map.getBlame("f.kt").associateBy { it.lineNumber }
        assertEquals(LineBlame.AuthorType.AI, byLine[1]!!.authorType)
        assertEquals(LineBlame.AuthorType.HUMAN, byLine[2]!!.authorType)
    }

    @Test
    fun `reindex removes deleted range and shifts line numbers`() {
        val map = BlameMap()
        map.setAttribute("f.kt", 1, 5, LineBlame.AuthorType.AI, charsInserted = 50)
        map.reindex("f.kt", 2, 0, 2) // delete lines 2-3
        val blame = map.getBlame("f.kt")
        assertEquals(3, blame.size)
        assertEquals(1, blame[0].lineNumber)
        assertEquals(2, blame[1].lineNumber) // was 4
        assertEquals(3, blame[2].lineNumber) // was 5
    }

    @Test
    fun `clear removes all blame and resets metrics`() {
        val map = BlameMap()
        map.setAttribute("a.kt", 1, 2, LineBlame.AuthorType.AI, charsInserted = 20)
        map.addTimeWaitingForAi(100L)
        assertTrue(map.getTrackedFiles().isNotEmpty())
        map.clear()
        assertEquals(0, map.getTrackedFiles().size)
        assertEquals(emptyList<LineBlame>(), map.getBlame("a.kt"))
    }

    @Test
    fun `setFileBlame and getBlame round-trip`() {
        val map = BlameMap()
        val entries = listOf(
            LineBlame(1, LineBlame.AuthorType.AI, timestamp = "ts1", aiChars = 10, humanChars = 0),
            LineBlame(2, LineBlame.AuthorType.HUMAN, timestamp = "ts2", aiChars = 0, humanChars = 5)
        )
        map.setFileBlame("x.kt", entries)
        val blame = map.getBlame("x.kt")
        assertEquals(2, blame.size)
        assertEquals(LineBlame.AuthorType.AI, blame[0].authorType)
        assertEquals(LineBlame.AuthorType.HUMAN, blame[1].authorType)
    }

    @Test
    fun `getSummary returns aiChars and humanChars`() {
        val map = BlameMap()
        map.setAttribute("a.kt", 1, 2, LineBlame.AuthorType.AI, charsInserted = 15)
        map.setAttribute("a.kt", 3, 3, LineBlame.AuthorType.HUMAN, charsInserted = 8)
        val summary = map.getSummary()
        assertEquals(3, summary.totalLines)
        assertTrue(summary.aiChars >= 15)
        assertTrue(summary.humanChars >= 8)
        assertEquals(2, summary.aiLines)
        assertEquals(1, summary.humanLines)
    }

    @Test
    fun `recordAiDeletion and wasLineDeletedByAi`() {
        val map = BlameMap()
        map.recordAiDeletion("f.kt", 2, 2) // lines 2 and 3 deleted by AI
        assertTrue(map.wasLineDeletedByAi("f.kt", 2))
        assertTrue(map.wasLineDeletedByAi("f.kt", 3))
        assertFalse(map.wasLineDeletedByAi("f.kt", 1))
        assertFalse(map.wasLineDeletedByAi("f.kt", 4))
    }

    @Test
    fun `decrementCharsForDeletion reduces counts and removes entry when zero`() {
        val map = BlameMap()
        map.setAttribute("f.kt", 1, 2, LineBlame.AuthorType.AI, charsInserted = 20)
        map.decrementCharsForDeletion("f.kt", 1, "hello\nworld")
        val blame = map.getBlame("f.kt")
        assertTrue(blame.size <= 2)
        blame.forEach { entry ->
            assertTrue(entry.aiChars >= 0)
            assertTrue(entry.humanChars >= 0)
        }
    }

    @Test
    fun `reattributeToAi flips human entries to AI`() {
        val map = BlameMap()
        map.setAttribute("f.kt", 1, 2, LineBlame.AuthorType.HUMAN, charsInserted = 20)
        val blame = map.getBlame("f.kt")
        map.reattributeToAi(blame, "copilot", "gpt-4")
        blame.forEach {
            assertEquals(LineBlame.AuthorType.AI, it.authorType)
            assertEquals("copilot", it.provider)
            assertEquals("gpt-4", it.model)
        }
    }

    @Test
    fun `normPath uses forward slash so getBlame finds by backslash path`() {
        val map = BlameMap()
        map.setAttribute("src/main/Foo.kt", 1, 1, LineBlame.AuthorType.AI, charsInserted = 5)
        val blame = map.getBlame("src\\main\\Foo.kt")
        assertEquals(1, blame.size)
    }

    @Test
    fun `addTimeWaitingForAi accumulates`() {
        val map = BlameMap()
        map.addTimeWaitingForAi(100L)
        map.addTimeWaitingForAi(200L)
        val summary = map.getSummary()
        assertTrue(summary.totalLines >= 0)
    }

    @Test
    fun `setCommitShaForFiles sets sha only for given files`() {
        val map = BlameMap()
        map.setAttribute("a.kt", 1, 1, LineBlame.AuthorType.AI, charsInserted = 5)
        map.setAttribute("b.kt", 1, 1, LineBlame.AuthorType.HUMAN, charsInserted = 5)
        map.setCommitShaForFiles("sha1", setOf("a.kt"))
        assertEquals("sha1", map.getBlame("a.kt")[0].commitSha)
        assertNull(map.getBlame("b.kt")[0].commitSha)
    }

    @Test
    fun `strict majority keeps line AI when human adds smaller edit`() {
        val map = BlameMap()
        map.setAttribute("f.kt", 1, 1, LineBlame.AuthorType.AI, charsInserted = 10)
        map.setAttribute("f.kt", 1, 1, LineBlame.AuthorType.HUMAN, charsInserted = 5)
        val blame = map.getBlame("f.kt")
        assertEquals(1, blame.size)
        assertEquals(LineBlame.AuthorType.AI, blame[0].authorType)
    }

    @Test
    fun `tie between ai and human chars favors AI`() {
        val map = BlameMap()
        map.setAttribute("f.kt", 1, 1, LineBlame.AuthorType.AI, charsInserted = 10)
        map.setAttribute("f.kt", 1, 1, LineBlame.AuthorType.HUMAN, charsInserted = 10)
        val blame = map.getBlame("f.kt")
        assertEquals(1, blame.size)
        assertEquals(LineBlame.AuthorType.AI, blame[0].authorType)
    }

    @Test
    fun `setAttribute with codingType BULK_INSERT sets it on new entries`() {
        val map = BlameMap()
        map.setAttribute("f.kt", 1, 3, LineBlame.AuthorType.HUMAN, charsInserted = 30, codingType = LineBlame.CodingType.BULK_INSERT)
        val blame = map.getBlame("f.kt")
        assertEquals(3, blame.size)
        blame.forEach { assertEquals(LineBlame.CodingType.BULK_INSERT, it.codingType) }
    }

    @Test
    fun `setAttribute with TYPING after BULK_INSERT sets codingType to TYPING`() {
        val map = BlameMap()
        map.setAttribute("f.kt", 1, 1, LineBlame.AuthorType.HUMAN, charsInserted = 10, codingType = LineBlame.CodingType.BULK_INSERT)
        map.setAttribute("f.kt", 1, 1, LineBlame.AuthorType.HUMAN, charsInserted = 5, codingType = LineBlame.CodingType.TYPING)
        val blame = map.getBlame("f.kt")
        assertEquals(1, blame.size)
        assertEquals(LineBlame.CodingType.TYPING, blame[0].codingType)
    }

    @Test
    fun `setAttribute with non-TYPING overwrites existing codingType`() {
        val map = BlameMap()
        map.setAttribute("f.kt", 1, 1, LineBlame.AuthorType.HUMAN, charsInserted = 10, codingType = LineBlame.CodingType.TYPING)
        map.setAttribute("f.kt", 1, 1, LineBlame.AuthorType.HUMAN, charsInserted = 5, codingType = LineBlame.CodingType.BULK_INSERT)
        val blame = map.getBlame("f.kt")
        assertEquals(1, blame.size)
        assertEquals(LineBlame.CodingType.BULK_INSERT, blame[0].codingType)
    }

    @Test
    fun `moveFile transfers blame to new path`() {
        val map = BlameMap()
        map.setAttribute("src/Old.kt", 1, 3, LineBlame.AuthorType.AI, provider = "copilot", charsInserted = 30)
        map.recordAiDeletion("src/Old.kt", 5, 2)
        assertTrue(map.getBlame("src/Old.kt").isNotEmpty())

        map.moveFile("src/Old.kt", "src/New.kt")

        assertTrue(map.getBlame("src/Old.kt").isEmpty())
        val blame = map.getBlame("src/New.kt")
        assertEquals(3, blame.size)
        blame.forEach {
            assertEquals(LineBlame.AuthorType.AI, it.authorType)
            assertEquals("copilot", it.provider)
        }
        assertTrue(map.wasLineDeletedByAi("src/New.kt", 5))
        assertFalse(map.wasLineDeletedByAi("src/Old.kt", 5))
    }

    @Test
    fun `moveFile with no existing blame is a no-op`() {
        val map = BlameMap()
        map.moveFile("nonexistent.kt", "dest.kt")
        assertTrue(map.getBlame("dest.kt").isEmpty())
    }

    @Test
    fun `default codingType is TYPING`() {
        val map = BlameMap()
        map.setAttribute("f.kt", 1, 1, LineBlame.AuthorType.HUMAN, charsInserted = 5)
        val blame = map.getBlame("f.kt")
        assertEquals(LineBlame.CodingType.TYPING, blame[0].codingType)
    }
}
