package ai.blamely.authorship

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StoreTest {
    @TempDir
    lateinit var repo: File

    @Test
    fun savesAndLoadsRoundTrip() {
        val wl = WorkingLog(
            lines = listOf(
                LineAttribution(1, 2, Author(AuthorType.HUMAN, genType = "human")),
                LineAttribution(3, 3, Author(AuthorType.AI, tool = "claude", genType = "chat")),
            ),
        )
        WorkingLogStore.save(repo.path, "main", "base0", "src/f.txt", wl, "h1\nh2\nai3\n")

        val loaded = WorkingLogStore.loadWorkingLog(repo.path, "main", "base0", "src/f.txt")
        assertNotNull(loaded)
        assertEquals(2, loaded!!.lines.size)
        assertEquals(AuthorType.HUMAN, loaded.lines[0].author.type)
        assertEquals(AuthorType.AI, loaded.lines[1].author.type)
        assertEquals("claude", loaded.lines[1].author.tool)
        assertEquals("src/f.txt", loaded.file)
        assertEquals("base0", loaded.baseSha)
    }

    @Test
    fun jsonMatchesCrossLanguageFormat() {
        val wl = WorkingLog(lines = listOf(LineAttribution(1, 1, Author(AuthorType.AI, tool = "codex", genType = "cli"))))
        WorkingLogStore.save(repo.path, "main", "base0", "f.txt", wl, "x\n")

        val json = WorkingLogStore.workingLogPath(repo.path, "main", "base0", "f.txt").readText()
        // snake_case keys + lowercase author values, identical to the Go/TS writers.
        assertTrue(json.contains("\"base_sha\""), "expected base_sha key")
        assertTrue(json.contains("\"blob_sha\""), "expected blob_sha key")
        assertTrue(json.contains("\"gen_type\""), "expected gen_type key")
        assertTrue(json.contains("\"author\": \"ai\""), "author must be lowercase 'ai', got: $json")
        assertTrue(!json.contains("\"AI\"") && !json.contains("\"HUMAN\""), "enum names must not leak: $json")
    }

    @Test
    fun pathSanitizesBranchSlashKeepsSpacedFilename() {
        val p = WorkingLogStore.workingLogPath("/tmp/repo", "feature/login", "abc", "pages/login page.html").path
        assertTrue(!p.contains("feature/login") && !p.contains("feature\\login"), "branch slash not sanitized: $p")
        assertTrue(p.contains("login page.html.json"), "spaced filename not preserved: $p")
    }
}
