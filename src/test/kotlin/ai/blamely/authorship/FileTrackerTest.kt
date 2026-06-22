package ai.blamely.authorship

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FileTrackerTest {
    private fun types(wl: WorkingLog?, n: Int): List<String> {
        val out = MutableList(n) { "" }
        for (r in wl?.lines ?: emptyList()) {
            var ln = r.start
            while (ln <= r.end && ln <= n) { out[ln - 1] = r.author.type.wire; ln++ }
        }
        return out
    }

    private val ai = Author(AuthorType.AI, tool = "copilot", genType = "completion")
    private fun human() = Author(AuthorType.HUMAN, genType = "human")

    @Test
    fun accumulatesHumanThenAIInOrder() {
        val t = FileTracker("", null)
        t.applyEdit("h1\nh2\n", human())
        t.applyEdit("h1\nh2\ndone();\n", ai)
        assertEquals(listOf("human", "human", "ai"), types(t.current(), 3))
        assertTrue(t.isDirty())
        t.markFlushed()
        assertFalse(t.isDirty())
    }

    @Test
    fun aiReEmitKeepsHumanLine() {
        val t = FileTracker("keep me\n", null)
        t.applyEdit("keep me\nai added\n", ai)
        assertEquals(listOf("human", "ai"), types(t.current(), 2))
    }

    @Test
    fun ignoresNoOpChange() {
        val t = FileTracker("x\n", null)
        t.applyEdit("x\n", ai)
        assertNull(t.current())
        assertFalse(t.isDirty())
    }
}
