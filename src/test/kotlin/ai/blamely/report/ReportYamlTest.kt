package ai.blamely.report

import ai.blamely.core.LineBlame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReportYamlTest {

    @Test
    fun `blameSnapshotToYaml produces valid YAML with expected keys`() {
        val entries = listOf(
            LineBlame(
                lineNumber = 1,
                authorType = LineBlame.AuthorType.AI,
                timestamp = "2025-01-01T00:00:00Z",
                model = "GPT-4",
                prompt = "write a function",
                interactionType = "chat_panel",
                aiChars = 20,
                humanChars = 0,
                changeType = LineBlame.ChangeType.ADD,
                newLineNumber = 1,
                oldLineNumber = null
            )
        )
        val entireBlame = mapOf("src/Main.kt" to entries)
        val yaml = ReportYaml.blameSnapshotToYaml(entireBlame)
        assertTrue(yaml.contains("src/Main.kt"))
        assertTrue(yaml.contains("lineNumber:"))
        assertTrue(yaml.contains("date:"))
        assertTrue(yaml.contains("authorType:"))
        assertFalse(yaml.contains("provider:"))
        assertTrue(yaml.contains("model:"))
        assertTrue(yaml.contains("prompt:"))
        assertTrue(yaml.contains("interactionType:"))
        assertTrue(yaml.contains("changeType:"))
    }

    @Test
    fun `blameSnapshotToYaml excludes aiChars and humanChars from report output`() {
        val entries = listOf(
            LineBlame(
                lineNumber = 1,
                authorType = LineBlame.AuthorType.HUMAN,
                timestamp = "ts",
                aiChars = 10,
                humanChars = 10
            )
        )
        val yaml = ReportYaml.blameSnapshotToYaml(mapOf("a.kt" to entries))
        assertFalse(yaml.contains("aiChars"))
        assertFalse(yaml.contains("humanChars"))
    }

    @Test
    fun `blameSnapshotToYaml handles empty map`() {
        val yaml = ReportYaml.blameSnapshotToYaml(emptyMap())
        assertTrue(yaml.isEmpty())
    }

    @Test
    fun `blameSnapshotToYaml includes codingType`() {
        val entries = listOf(
            LineBlame(
                lineNumber = 1,
                authorType = LineBlame.AuthorType.HUMAN,
                timestamp = "ts",
                codingType = LineBlame.CodingType.BULK_INSERT
            ),
            LineBlame(
                lineNumber = 2,
                authorType = LineBlame.AuthorType.AI,
                timestamp = "ts",
                codingType = LineBlame.CodingType.TYPING
            )
        )
        val yaml = ReportYaml.blameSnapshotToYaml(mapOf("a.kt" to entries))
        assertTrue(yaml.contains("codingType:"))
        assertTrue(yaml.contains("BULK_INSERT"))
        assertTrue(yaml.contains("TYPING"))
    }

    @Test
    fun `blameSnapshotToYaml includes default TYPING codingType`() {
        val entries = listOf(
            LineBlame(lineNumber = 1, authorType = LineBlame.AuthorType.HUMAN, timestamp = "ts")
        )
        val yaml = ReportYaml.blameSnapshotToYaml(mapOf("a.kt" to entries))
        assertTrue(yaml.contains("TYPING"))
    }

    @Test
    fun `ReportMetrics default values`() {
        val m = ReportMetrics()
        assertEquals(0L, m.firstStartCodingTimeMs)
        assertEquals(0L, m.timeWaitingForAiMs)
    }

    @Test
    fun `detectorHookPreamble includes v2 ai delete totals`() {
        val blame = mapOf(
            "f.kt" to listOf(
                LineBlame(lineNumber = 1, authorType = LineBlame.AuthorType.AI, timestamp = "t", changeType = LineBlame.ChangeType.ADD),
                LineBlame(
                    lineNumber = 2,
                    authorType = LineBlame.AuthorType.HUMAN,
                    timestamp = "t",
                    changeType = LineBlame.ChangeType.DELETE,
                    oldLineNumber = 2
                )
            )
        )
        val totals = ReportYaml.computeHookTotalsFromBlameSnapshot(blame)
        assertEquals(1, totals.aiLinesAdded)
        assertEquals(0, totals.aiLinesDeleted)
        assertEquals(0, totals.humanLinesAdded)
        assertEquals(1, totals.humanLinesDeleted)
        val p = ReportYaml.detectorHookPreamble(totals)
        assertTrue(p.contains("# ai_lines_deleted: 0"))
        assertTrue(p.contains("# human_lines_deleted: 1"))
    }
}
