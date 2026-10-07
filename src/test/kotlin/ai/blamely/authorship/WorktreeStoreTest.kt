package ai.blamely.authorship

import ai.blamely.cli.CliRepoId
import ai.blamely.git.GitUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

class WorktreeStoreTest {
    @TempDir lateinit var temp: File

    @AfterEach fun clearCaches() = GitUtils.clearRepoRootCache()

    private fun git(root: File, vararg args: String): String {
        val pb = ProcessBuilder("git", "-c", "core.hooksPath=", "-C", root.path, *args).redirectErrorStream(true)
        pb.environment().putAll(mapOf("GIT_AUTHOR_NAME" to "Test", "GIT_AUTHOR_EMAIL" to "test@example.com",
            "GIT_COMMITTER_NAME" to "Test", "GIT_COMMITTER_EMAIL" to "test@example.com"))
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS), "git timed out")
        assertEquals(0, p.exitValue(), out)
        return out.trim()
    }

    @Test fun `working logs and baselines are private to each checkout`() {
        val main = File(temp, "main").apply { mkdirs() }
        val first = File(temp, "first worktree")
        val second = File(temp, "second")
        git(main, "init", "-q", "-b", "main")
        File(main, "file.txt").writeText("original\n")
        git(main, "add", ".")
        git(main, "commit", "-qm", "initial")
        val sha = git(main, "rev-parse", "HEAD")
        git(main, "worktree", "add", "-q", "-b", "feature/worktree", first.path)
        git(main, "worktree", "add", "-q", "--detach", second.path)

        assertEquals(CliRepoId.get(main.path), CliRepoId.get(first.path))
        assertEquals(CliRepoId.get(main.path), CliRepoId.get(second.path))
        assertEquals("feature/worktree", GitUtils.getBranchName(first.path))
        assertNull(GitUtils.getBranchName(second.path))
        for ((root, tool) in listOf(main to "claude", first to "copilot", second to "codex")) {
            val log = WorkingLog(lines = listOf(LineAttribution(1, 1, Author(AuthorType.AI, tool = tool, genType = "chat"))))
            WorkingLogStore.save(root.path, "same", sha, "file.txt", log, "$tool\n")
            val gitDir = git(root, "rev-parse", "--absolute-git-dir")
            val expected = File(gitDir, "blamely/working_logs/same/$sha/file.txt.json")
            assertEquals(expected.canonicalFile, WorkingLogStore.workingLogPath(root.path, "same", sha, "file.txt").canonicalFile)
            assertEquals("$tool\n", WorkingLogStore.baselinePath(root.path, "same", sha, "file.txt").readText())
            assertEquals(tool, WorkingLogStore.loadWorkingLog(root.path, "same", sha, "file.txt")!!.lines[0].author.tool)
        }
        assertTrue(File(first, ".git").isFile)
        assertEquals("claude", WorkingLogStore.loadWorkingLog(main.path, "same", sha, "file.txt")!!.lines[0].author.tool)
        File(first, "file.txt").writeText("worktree commit\n")
        git(first, "commit", "-qam", "worktree only")
        assertNotEquals(sha, git(first, "rev-parse", "HEAD"))
        assertEquals(sha, git(main, "rev-parse", "HEAD"))
    }
}
