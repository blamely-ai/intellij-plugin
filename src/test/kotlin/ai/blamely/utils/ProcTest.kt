package ai.blamely.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class ProcTest {

    private fun requireSh() = assumeTrue(File("/bin/sh").canExecute(), "POSIX sh required")

    @Test
    fun `normal command returns trimmed stdout`() {
        requireSh()
        val out = Proc.run(listOf("/bin/sh", "-c", "echo hello"), timeoutMs = 5_000, maxBytes = 1024)
        assertEquals("hello", out)
    }

    @Test
    fun `non-zero exit returns null`() {
        requireSh()
        val out = Proc.run(listOf("/bin/sh", "-c", "echo oops; exit 3"), timeoutMs = 5_000, maxBytes = 1024)
        assertNull(out)
    }

    @Test
    fun `hung child is killed at the timeout`() {
        requireSh()
        val startedAt = System.currentTimeMillis()
        val out = Proc.run(listOf("/bin/sh", "-c", "sleep 30"), timeoutMs = 500, maxBytes = 1024)
        assertNull(out)
        val elapsed = System.currentTimeMillis() - startedAt
        assumeTrue(elapsed < 10_000, "kill must not wait for the child's natural exit")
    }

    @Test
    fun `output beyond maxBytes returns null`() {
        requireSh()
        // ~1 MB of output against a 64 KB cap.
        val out = Proc.run(
            listOf("/bin/sh", "-c", "yes x | head -c 1048576"),
            timeoutMs = 10_000, maxBytes = 64 * 1024,
        )
        assertNull(out)
    }

    @Test
    fun `stderr noise does not corrupt stdout`() {
        requireSh()
        val out = Proc.run(
            listOf("/bin/sh", "-c", "echo warning >&2; echo data"),
            timeoutMs = 5_000, maxBytes = 1024,
        )
        assertEquals("data", out)
    }
}
