package ai.blamely

import ai.blamely.git.GitUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GitUtilsTest {

    @TempDir
    lateinit var tempDir: File

    private fun ensureGitUser(cwd: String) {
        runGit(cwd, "config", "user.email", "test@blamely.test")
        runGit(cwd, "config", "user.name", "Blamely Test")
    }

    @Test
    fun `run rev-parse HEAD returns commit sha after init and commit`() {
        val cwd = tempDir.absolutePath
        runGit(cwd, "init")
        ensureGitUser(cwd)
        File(tempDir, "f.txt").writeText("hello")
        runGit(cwd, "add", "f.txt")
        runGit(cwd, "commit", "-m", "first")
        val sha = GitUtils.run(cwd, "rev-parse", "HEAD")
        assertNotNull(sha)
        assertTrue(sha!!.length >= 40)
    }

    @Test
    fun `parseDiffShortStat parses git shortstat line`() {
        val s = GitUtils.parseDiffShortStat(" 3 files changed, 25 insertions(+), 10 deletions(-)")
        assertEquals(25, s.insertions)
        assertEquals(10, s.deletions)
        assertEquals(3, s.filesChanged)
    }

    @Test
    fun `parseNumstat maps paths and skips binary`() {
        val m = GitUtils.parseNumstat("12\t3\tsrc/Foo.kt\n-\t-\tbinary.dat\n")
        assertEquals(12 to 3, m["src/Foo.kt"])
        assertFalse(m.containsKey("binary.dat"))
    }

    private fun runGit(cwd: String, vararg args: String) {
        val pb = ProcessBuilder("git", *args).directory(File(cwd)).redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor() == 0, "git ${args.joinToString(" ")} failed: $out")
    }
}
