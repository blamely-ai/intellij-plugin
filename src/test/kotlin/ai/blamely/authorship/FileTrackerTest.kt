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

    // Regression: an agent (e.g. Claude Code) creates a file via Write — the keystroke
    // tracker never sees it, but the CLI writes a working log crediting the AI. When the
    // editor FIRST tracks the file (a later human paste), WorkingLogTracker.seedTracker
    // seeds the FileTracker from that prior log, not null. Seeding from null defaulted
    // every untouched AI line to Human and the flush clobbered the file's attribution
    // (repro: student-register.html committed as all-Human).
    @Test
    fun seedsFromPriorLogSoAgentFileIsNotClobbered() {
        val claude = Author(AuthorType.AI, tool = "claude", genType = "chat")
        val agentContent = "a1\na2\na3\n"
        val prior = FileTracker("", null)
        prior.applyEdit(agentContent, claude)
        assertEquals(listOf("ai", "ai", "ai"), types(prior.current(), 3))

        // Editor first sees the file at a human paste, seeded from the prior log + baseline.
        val t = FileTracker(agentContent, prior.current())
        t.applyEdit("a1\na2\npasted\na3\n", human())
        assertEquals(listOf("ai", "ai", "human", "ai"), types(t.current(), 4))
    }

    @Test
    fun withoutPriorLogUntouchedLinesDefaultToHumanTheBug() {
        val t = FileTracker("a1\na2\na3\n", null)
        t.applyEdit("a1\na2\npasted\na3\n", human())
        assertEquals(listOf("human", "human", "human", "human"), types(t.current(), 4))
    }
}
