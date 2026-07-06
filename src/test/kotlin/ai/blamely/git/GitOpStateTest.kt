package ai.blamely.git

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** GitOpState poller state machine — markers + stash-reflog transitions.
 *  Mirrors the VS Code plugin's GitOpState.test.ts; keep both in sync. */
class GitOpStateTest {

    @TempDir
    lateinit var tempDir: File

    private fun runGit(cwd: String, vararg args: String) {
        val p = ProcessBuilder(listOf("git", "-C", cwd) + args)
            .redirectErrorStream(true)
            .apply {
                environment()["GIT_AUTHOR_NAME"] = "t"
                environment()["GIT_AUTHOR_EMAIL"] = "t@t"
                environment()["GIT_COMMITTER_NAME"] = "t"
                environment()["GIT_COMMITTER_EMAIL"] = "t@t"
            }
            .start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
    }

    private fun initRepo(): String {
        val cwd = tempDir.absolutePath
        runGit(cwd, "init", "-q", "-b", "main")
        runGit(cwd, "config", "user.email", "t@t")
        runGit(cwd, "config", "user.name", "t")
        return cwd
    }

    @Test
    fun `marker files toggle isActive`() {
        val repo = initRepo()
        val gitDir = File(repo, ".git")
        val s = GitOpState()
        s.poll(repo)
        assertFalse(s.isActive(), "fresh repo: inactive")

        File(gitDir, "CHERRY_PICK_HEAD").writeText("a".repeat(40) + "\n")
        s.poll(repo)
        assertTrue(s.isActive(), "CHERRY_PICK_HEAD present: active")

        File(gitDir, "CHERRY_PICK_HEAD").delete()
        s.poll(repo)
        assertFalse(s.isActive(), "marker removed: inactive again")
    }

    @Test
    fun `a real git stash opens the stash window`() {
        val repo = initRepo()
        File(repo, "f.txt").writeText("base\n")
        runGit(repo, "add", ".")
        runGit(repo, "commit", "-q", "-m", "c1")

        val s = GitOpState()
        s.poll(repo) // baseline: no stash reflog yet
        assertFalse(s.isActive())

        File(repo, "f.txt").writeText("base\nwork\n")
        runGit(repo, "stash")
        s.poll(repo)

        // Pop deletes/touches the reflog → transition → window opens.
        runGit(repo, "stash", "pop")
        s.poll(repo)
        assertTrue(s.isActive(), "stash pop must open the stash window")
    }
}
