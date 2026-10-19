package ai.blamely.git

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/**
 * [GitUtils.toRepoRelativePath] must name a file as git does when the work tree is
 * reached through an alias — a directory junction here, a Windows profile on a
 * mounted volume in the field — where git reports the real path as the top level.
 */
class GitUtilsRepoRelativePathTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var real: File
    private lateinit var alias: File

    @BeforeEach
    fun setUp() {
        GitUtils.clearRepoRootCache()
        val base = tempDir.toPath().toRealPath().toFile()
        real = File(base, "real/repo").also { File(it, "src/deep").mkdirs() }
        runGit(real, "init", "-q")
        File(real, "README.md").writeText("r\n")
        File(real, "src/deep/a.kt").writeText("a\n")
        runGit(real, "add", ".")
        runGit(real, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "c1")
        val link = File(base, "alias")
        if (File.separatorChar == '\\') {
            // A junction needs no privilege, unlike a Windows symlink.
            ProcessBuilder("cmd", "/c", "mklink", "/J", link.path, File(base, "real").path)
                .redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
        } else {
            Files.createSymbolicLink(link.toPath(), File(base, "real").toPath())
        }
        alias = File(link, "repo")
        assumeTrue(File(alias, "README.md").exists(), "could not create the alias")
    }

    @AfterEach
    fun tearDown() {
        GitUtils.clearRepoRootCache()
    }

    @Test
    fun `names files reached through an alias as git does`() {
        val root = GitUtils.getRepoRoot(alias.path)
        assertNotNull(root)
        val cases = listOf(
            File(alias, "README.md") to "README.md", // top level: empty --show-prefix
            File(alias, "src/deep/a.kt") to "src/deep/a.kt",
            File(alias, "src/deep/new.kt") to "src/deep/new.kt", // not written yet
            File(alias, "brand/new/x.kt") to "brand/new/x.kt", // new subtree
            File(real, "src/deep/a.kt") to "src/deep/a.kt",
            File(tempDir, "elsewhere.kt") to null,
        )
        for ((file, want) in cases) {
            assertEquals(want, GitUtils.toRepoRelativePath(root!!, file.path), file.path)
            assertEquals(want, GitUtils.toRepoRelativePath(real.path, file.path), "${file.path} (real root)")
        }
    }

    @Test
    fun `matches a forward-slash IDE path against git's root on Windows`() {
        assumeTrue(File.separatorChar == '\\')
        // git prints `C:/…`; VirtualFile.path is slash-separated too.
        val root = GitUtils.getRepoRoot(real.path)!!.replace('\\', '/')
        val ide = File(real, "README.md").path.replace('\\', '/')
        val lowerDrive = ide[0].lowercaseChar() + ide.substring(1)
        assertEquals("README.md", GitUtils.toRepoRelativePath(root, ide, askGit = false))
        assertEquals("README.md", GitUtils.toRepoRelativePath(root, lowerDrive, askGit = false))
    }

    @Test
    fun `blameKey is the same through the alias and the real path`() {
        // The editor keys a file by the path it was opened under; the repo-wide pass
        // by git's real path. Different keys put the file in the map twice.
        assertEquals(
            GitUtils.blameKey(File(real, "src/deep/a.kt").path),
            GitUtils.blameKey(File(alias, "src/deep/a.kt").path),
        )
    }

    private fun runGit(cwd: File, vararg args: String) {
        // core.hooksPath= keeps an installed Blamely's global post-commit hook from
        // recording this throwaway repo in the user's real database.
        val pb = ProcessBuilder("git", "-c", "core.hooksPath=", *args).directory(cwd).redirectErrorStream(true)
        val p = pb.start()
        p.inputStream.bufferedReader().readText()
        p.waitFor()
    }
}
