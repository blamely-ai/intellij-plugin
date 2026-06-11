package ai.blamely.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class CliNoteParserTest {
    @Test
    fun parse_readsBranchMessageAndCodingTime() {
        val raw = """
            {
              "schema": 1,
              "commit": "1d90c1da58c0e8e659c873b34e82e8a5d92a720e",
              "branch": "master",
              "message": "test33",
              "coding_time_nanos": 140219715000,
              "totals": { "ai_lines": 57, "human_lines": 0, "deleted_lines": 0, "files": 2 },
              "by_tool": { "copilot": { "lines": 57 } },
              "by_gen_type": { "chat": 0, "cli": 0, "completion": 57 }
            }
        """.trimIndent()

        val note = CliNoteParser.parse(raw)
        assertNotNull(note)
        assertEquals("master", note!!.branch)
        assertEquals("test33", note.message)
        assertEquals(140219L, CliNoteParser.codingTimeMs(note))
        assertEquals(listOf("copilot"), CliNoteParser.models(note))
    }

    @Test
    fun parse_acceptsSchema2WithRangeBasedLines() {
        // Schema 2 collapses per-line entries into start/end ranges and may
        // carry ai_deleted_lines on a deletion-only commit (no ai/human added
        // lines). The History tool window must still surface these commits.
        val raw = """
            {
              "schema": 2,
              "commit": "e1a13f1972a417faf812cb68fc25f4e8cae2020d",
              "branch": "master",
              "message": "kerim",
              "coding_time_nanos": 22858394000,
              "totals": { "ai_lines": 0, "human_lines": 0, "deleted_lines": 19, "ai_deleted_lines": 19, "files": 1 },
              "by_tool": {},
              "by_gen_type": { "chat": 19, "cli": 0, "completion": 0, "human": 0 },
              "files": [{"path":"student-registration.js","type":"MODIFIED","added":0,"deleted":19,
                "lines":[{"start":27,"end":45,"type":"delete","author_type":"AI","tool":"claude","model":"claude-sonnet-4-6","gen_type":"chat"}]}]
            }
        """.trimIndent()

        val note = CliNoteParser.parse(raw)
        assertNotNull(note)
        assertEquals(19, note!!.totals.deletedLines)
        assertEquals(19, note.totals.aiDeletedLines)
        assertEquals(listOf("chat"), CliNoteParser.genTypes(note))
    }

    @Test
    fun parse_rejectsUnknownSchema() {
        val raw = """{"schema": 3, "commit": "abc123", "totals": {"ai_lines": 1, "human_lines": 0, "deleted_lines": 0, "files": 1}}"""
        assertEquals(null, CliNoteParser.parse(raw))
    }
}
