package ai.blamely

import ai.blamely.git.GitUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for GitUtils: rev-parse, notes add/show in a real temp git repo.
 * Verifies that git notes are created and readable (refs/notes/blamely).
 */
class GitUtilsTest {

    @TempDir
    lateinit var tempDir: File

    /** Configure git user so commits succeed in CI (e.g. GitHub Actions) where user.name/email may be unset. */
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
    fun `addGitNote and getNoteContent round-trip`() {
        val cwd = tempDir.absolutePath
        runGit(cwd, "init")
        ensureGitUser(cwd)
        File(tempDir, "f.txt").writeText("hello")
        runGit(cwd, "add", "f.txt")
        runGit(cwd, "commit", "-m", "first")
        val sha = requireNotNull(GitUtils.run(cwd, "rev-parse", "HEAD")) { "no HEAD" }
        val content = "report:\n  commit: $sha\n---\nblames: {}"
        val added = GitUtils.addGitNote(cwd, sha, content)
        assertTrue(added, "addGitNote should succeed")
        val read = GitUtils.getNoteContent(cwd, sha)
        assertNotNull(read, "getNoteContent should return note body")
        assertEquals(content, read!!.trim(), "note content should match")
    }

    @Test
    fun `runSuccess returns true for successful command`() {
        val cwd = tempDir.absolutePath
        runGit(cwd, "init")
        assertTrue(GitUtils.runSuccess(cwd, "status"))
    }

    @Test
    fun `runSuccess returns false for invalid ref`() {
        val cwd = tempDir.absolutePath
        runGit(cwd, "init")
        assertFalse(GitUtils.runSuccess(cwd, "rev-parse", "nonexistent-ref-name-12345"))
    }

    @Test
    fun `getFilesChangedInCommit returns only files changed in that commit`() {
        val cwd = tempDir.absolutePath
        runGit(cwd, "init")
        ensureGitUser(cwd)
        File(tempDir, "a.txt").writeText("a")
        runGit(cwd, "add", "a.txt")
        runGit(cwd, "commit", "-m", "add a")
        val sha1 = requireNotNull(GitUtils.run(cwd, "rev-parse", "HEAD"))
        File(tempDir, "b.txt").writeText("b")
        runGit(cwd, "add", "b.txt")
        runGit(cwd, "commit", "-m", "add b")
        val sha2 = requireNotNull(GitUtils.run(cwd, "rev-parse", "HEAD"))
        val files1 = GitUtils.getFilesChangedInCommit(cwd, sha1)
        val files2 = GitUtils.getFilesChangedInCommit(cwd, sha2)
        assertTrue(files1.contains("a.txt"), "first commit should contain a.txt: $files1")
        assertTrue(files2.contains("b.txt"), "second commit should contain b.txt: $files2")
    }

    @Test
    fun `repoRelativeToProjectRelative when base equals repo returns paths as-is`() {
        val repo = "/repo"
        val base = "/repo"
        val paths = listOf("a.txt", "src/b.kt")
        val out = GitUtils.repoRelativeToProjectRelative(repo, base, paths)
        assertEquals(paths.toSet(), out)
    }

    @Test
    fun `repoRelativeToProjectRelative when project is subdir strips prefix`() {
        val repo = "/repo"
        val base = "/repo/myproject"
        val paths = listOf("myproject/index.html", "myproject/src/a.kt", "other/x.txt")
        val out = GitUtils.repoRelativeToProjectRelative(repo, base, paths)
        assertTrue(out.contains("index.html"))
        assertTrue(out.contains("src/a.kt"))
        assertFalse(out.contains("other/x.txt"))
    }

    @Test
    fun `getAddedLineNumbersInCommit returns line numbers of added lines`() {
        val cwd = tempDir.absolutePath
        runGit(cwd, "init")
        ensureGitUser(cwd)
        File(tempDir, "f.txt").writeText("line1\nline2\nline3\n")
        runGit(cwd, "add", "f.txt")
        runGit(cwd, "commit", "-m", "add f")
        val sha = requireNotNull(GitUtils.run(cwd, "rev-parse", "HEAD"))
        val added = GitUtils.getAddedLineNumbersInCommit(cwd, sha, "f.txt")
        assertTrue(added.isNotEmpty(), "should have added lines")
        assertEquals(listOf(1, 2, 3), added)
    }

    @Test
    fun `addGitNote accepts full report format like CommitListener`() {
        val cwd = tempDir.absolutePath
        runGit(cwd, "init")
        ensureGitUser(cwd)
        File(tempDir, "x.kt").writeText("fun foo() {}")
        runGit(cwd, "add", "x.kt")
        runGit(cwd, "commit", "-m", "add x")
        val sha = requireNotNull(GitUtils.run(cwd, "rev-parse", "HEAD")) { "no HEAD" }
        val yamlReport = """
            detector_version: "0.2.0"
            generated_at: "2025-01-01T00:00:00Z"
            commit_hash: $sha
            branch: main
        """.trimIndent()
        val blameSnapshot = """{"src/Main.kt":[{"line_number":1,"author_type":"ai","provider":"copilot"}]}"""
        val noteContent = "$yamlReport\n---\nblames:\n$blameSnapshot"
        assertTrue(GitUtils.addGitNote(cwd, sha, noteContent), "addGitNote with report format should succeed")
        val read = GitUtils.getNoteContent(cwd, sha)
        assertNotNull(read)
        assertTrue(read!!.contains("detector_version"))
        assertTrue(read.contains("blames"))
    }

    @Test
    fun `note with synthetic human blame has non-empty blames`() {
        val cwd = tempDir.absolutePath
        runGit(cwd, "init")
        ensureGitUser(cwd)
        File(tempDir, "newfile.txt").writeText("first\nsecond\nthird\n")
        runGit(cwd, "add", "newfile.txt")
        runGit(cwd, "commit", "-m", "add newfile")
        val sha = requireNotNull(GitUtils.run(cwd, "rev-parse", "HEAD"))
        val added = GitUtils.getAddedLineNumbersInCommit(cwd, sha, "newfile.txt")
        assertTrue(added.isNotEmpty(), "getAddedLineNumbersInCommit should return added lines")
        val ts = java.time.Instant.now().toString()
        val synthetic = added.map { line ->
            """{"lineNumber":$line,"authorType":"HUMAN","timestamp":"$ts","commitSha":"$sha","humanChars":10}"""
        }.joinToString(",")
        val blameSnapshot = """{"newfile.txt":[$synthetic]}"""
        val yamlReport = "scope: \"this_commit\"\ncommit_hash: \"$sha\"\n"
        val noteContent = "$yamlReport---\nblames:\n$blameSnapshot"
        assertTrue(GitUtils.addGitNote(cwd, sha, noteContent), "addGitNote with synthetic blame should succeed")
        val read = GitUtils.getNoteContent(cwd, sha)
        assertNotNull(read)
        assertTrue(read!!.contains("newfile.txt"))
        assertTrue(read.contains("blames"))
    }

    @Test
    fun `parseDiffShortStat parses git shortstat line`() {
        val s = GitUtils.parseDiffShortStat(" 3 files changed, 25 insertions(+), 10 deletions(-)")
        assertEquals(25, s.insertions)
        assertEquals(10, s.deletions)
        assertEquals(3, s.filesChanged)
    }

    @Test
    fun `parseDiffShortStat handles insertions only`() {
        val s = GitUtils.parseDiffShortStat(" 1 file changed, 5 insertions(+)")
        assertEquals(5, s.insertions)
        assertEquals(0, s.deletions)
        assertEquals(1, s.filesChanged)
    }

    @Test
    fun `parseNumstat maps paths and skips binary`() {
        val m = GitUtils.parseNumstat("12\t3\tsrc/Foo.kt\n-\t-\tbinary.dat\n")
        assertEquals(12 to 3, m["src/Foo.kt"])
        assertFalse(m.containsKey("binary.dat"))
    }

    @Test
    fun `parseUnifiedDiff counts added and deleted lines`() {
        val patch = """
            @@ -1,2 +1,4 @@
             line1
            -old
            +new1
            +new2
            +new3
             line2
        """.trimIndent()
        val s = GitUtils.parseUnifiedDiff(patch)
        assertEquals(3, s.addedCount)
        assertEquals(1, s.deletedCount)
    }

    private fun runGit(cwd: String, vararg args: String) {
        val pb = ProcessBuilder("git", *args).directory(File(cwd)).redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor() == 0, "git ${args.joinToString(" ")} failed: $out")
    }
}
