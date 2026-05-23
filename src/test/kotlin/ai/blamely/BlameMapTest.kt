package ai.blamely

import ai.blamely.core.BlameMap
import ai.blamely.core.LineBlame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BlameMapTest {

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
    fun `getSummary counts ai and human lines`() {
        val map = BlameMap()
        map.setFileBlame(
            "a.kt",
            listOf(
                LineBlame(1, LineBlame.AuthorType.AI, timestamp = "t", aiChars = 15, humanChars = 0),
                LineBlame(2, LineBlame.AuthorType.AI, timestamp = "t", aiChars = 5, humanChars = 0),
                LineBlame(3, LineBlame.AuthorType.HUMAN, timestamp = "t", aiChars = 0, humanChars = 8),
            )
        )
        val summary = map.getSummary()
        assertEquals(3, summary.totalLines)
        assertEquals(2, summary.aiLines)
        assertEquals(1, summary.humanLines)
        assertEquals(20, summary.aiChars)
        assertEquals(8, summary.humanChars)
    }

    @Test
    fun `clear removes all blame`() {
        val map = BlameMap()
        map.setFileBlame("a.kt", listOf(LineBlame(1, LineBlame.AuthorType.AI, timestamp = "t", aiChars = 1, humanChars = 0)))
        map.clear()
        assertTrue(map.getTrackedFiles().isEmpty())
        assertTrue(map.getBlame("a.kt").isEmpty())
    }

    @Test
    fun `normPath uses forward slash`() {
        val map = BlameMap()
        map.setFileBlame("src/main/Foo.kt", listOf(LineBlame(1, LineBlame.AuthorType.AI, timestamp = "t", aiChars = 5, humanChars = 0)))
        assertEquals(1, map.getBlame("src\\main\\Foo.kt").size)
    }
}
