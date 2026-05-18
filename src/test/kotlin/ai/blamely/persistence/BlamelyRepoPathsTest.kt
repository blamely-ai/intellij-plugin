package ai.blamely.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BlamelyRepoPathsTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `safeBranchName maps dot segments to HEAD`() {
        assertEquals("HEAD", BlamelyRepoPaths.safeBranchName("."))
        assertEquals("HEAD", BlamelyRepoPaths.safeBranchName(".."))
    }

    @Test
    fun `safeBranchName replaces unsafe characters`() {
        assertEquals("feature-foo", BlamelyRepoPaths.safeBranchName("feature/foo"))
        assertEquals("dir-sub", BlamelyRepoPaths.safeBranchName("dir\\sub"))
        assertEquals("HEAD", BlamelyRepoPaths.safeBranchName(null))
        assertEquals("HEAD", BlamelyRepoPaths.safeBranchName("   "))
        assertEquals("a-b-c-d", BlamelyRepoPaths.safeBranchName("a:b*c?d"))
    }

    @Test
    fun `repo paths live under git dir per branch`() {
        val gitDir = File(tempDir, ".git")
        val branch = "feature/awesome"
        val branchKey = BlamelyRepoPaths.safeBranchName(branch)
        val open = BlamelyRepoPaths.openDir(gitDir, branch)
        val stash = BlamelyRepoPaths.stashDir(gitDir, branch)
        val closed = BlamelyRepoPaths.closedDir(gitDir, branch)
        val report = BlamelyRepoPaths.reportFile(gitDir, branch)
        assertEquals(File(File(File(gitDir, "blamely"), branchKey), "open"), open)
        assertEquals(File(File(File(gitDir, "blamely"), branchKey), "stash"), stash)
        assertEquals(File(File(File(gitDir, "blamely"), branchKey), "closed"), closed)
        assertEquals(File(File(File(gitDir, "blamely"), branchKey), "report.yml"), report)
    }

    @Test
    fun `intellij tracked session file resolves under repos repo-name bucket`() {
        val ijHome = File(tempDir, "ij-home")
        System.setProperty("blamely.intellij.home", ijHome.absolutePath)
        try {
            val repo = File(tempDir, "repo").also { it.mkdirs() }
            val sessionId = "abc-123"
            val canon = repo.canonicalPath
            val bucket = BlamelyUserRepoPaths.repoBucketName(canon)
            val expected = File(File(File(File(ijHome, "repos"), bucket), "sessions"), sessionId).resolve("blamely.json")
            assertEquals(expected.canonicalFile, BlamelyRepoPaths.intellijTrackedSessionFile(canon, sessionId).canonicalFile)
        } finally {
            System.clearProperty("blamely.intellij.home")
        }
    }

    @Test
    fun `workspace tracked session file is legacy vscode layout`() {
        val repo = tempDir
        val sessionId = "abc-123"
        val target = BlamelyRepoPaths.workspaceTrackedSessionFile(repo, sessionId)
        assertEquals(File(File(File(repo, "blamely/sessions"), sessionId), "blamely.json"), target)
    }

    @Test
    fun `legacy session root respects BLAMELY_SESSION_HOME`() {
        // We cannot mutate System.getenv from JVM; assert the env override indirectly by
        // verifying that without an override we land in $HOME/.blamely/session.
        val expectedFallback = File(File(System.getProperty("user.home")), ".blamely/session")
        val override = System.getenv("BLAMELY_SESSION_HOME")
        if (override.isNullOrBlank()) {
            assertEquals(expectedFallback, BlamelyRepoPaths.legacySessionRoot())
        } else {
            assertEquals(File(override), BlamelyRepoPaths.legacySessionRoot())
        }
    }

    @Test
    fun `legacy branch dir composes repoKey and branchKey`() {
        val rk = "deadbeefdeadbeef"
        val bk = "main"
        val dir = BlamelyRepoPaths.legacyBranchDir(rk, bk)
        assertTrue(dir.path.contains("/$rk/$bk") || dir.path.contains("\\$rk\\$bk"))
    }
}
