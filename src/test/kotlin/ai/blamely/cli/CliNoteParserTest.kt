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
}
