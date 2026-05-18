package ai.blamely.git

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Exercises the new path-overload of [GitUtils.getRepoRoot]: it must accept either a file
 * or a directory and resolve the enclosing repo. Previously, callers passed
 * `document.uri.fsPath` directly which silently broke session creation when the path was
 * a file (git was given a file as cwd).
 */
class GitUtilsRepoRootTest {

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        GitUtils.clearRepoRootCache()
        runGit(tempDir.absolutePath, "init")
        runGit(tempDir.absolutePath, "config", "user.email", "test@blamely.test")
        runGit(tempDir.absolutePath, "config", "user.name", "Blamely Test")
    }

    @AfterEach
    fun tearDown() {
        GitUtils.clearRepoRootCache()
    }

    @Test
    fun `getRepoRoot with directory returns repo root`() {
        val root = GitUtils.getRepoRoot(tempDir.absolutePath)
        assertNotNull(root)
        assertEquals(File(tempDir.absolutePath).canonicalPath, File(root!!).canonicalPath)
    }

    @Test
    fun `getRepoRoot with file path uses parent directory`() {
        val sub = File(tempDir, "src/app").also { it.mkdirs() }
        val file = File(sub, "main.kt").also { it.writeText("fun main(){}") }
        val root = GitUtils.getRepoRoot(file.absolutePath)
        assertNotNull(root, "file-path overload should resolve to enclosing repo")
        assertEquals(File(tempDir.absolutePath).canonicalPath, File(root!!).canonicalPath)
    }

    @Test
    fun `getRepoRoot returns null for blank path`() {
        assertNull(GitUtils.getRepoRoot(""))
    }

    private fun runGit(cwd: String, vararg args: String) {
        val pb = ProcessBuilder("git", *args).directory(File(cwd)).redirectErrorStream(true)
        val p = pb.start()
        p.inputStream.bufferedReader().readText()
        p.waitFor()
    }
}
