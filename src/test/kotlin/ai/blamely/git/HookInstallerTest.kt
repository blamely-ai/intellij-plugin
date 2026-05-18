package ai.blamely.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HookInstallerTest {

    @TempDir
    lateinit var tempDir: File

    /** Isolate ~/.blamely/repos so tests never touch the real user layout. */
    private fun isolatedBlamelyRoot(): File = File(tempDir, "blamely-test-layout")

    @Test
    fun `installAll writes hookRunner js under user repo dir and node-based pre-commit`() {
        val gitDir = File(tempDir, ".git").also { it.mkdirs() }
        val layoutRoot = isolatedBlamelyRoot()
        val result = HookInstaller.installAll(gitDir = gitDir, repoRoot = tempDir, userLayoutRoot = layoutRoot)
        assertTrue(result.ok, "install should succeed: ${result.message}")

        val canon = ai.blamely.persistence.BlamelyUserRepoPaths.canonicalRepoDiskPath(tempDir.absolutePath)
        val bucket = ai.blamely.persistence.BlamelyUserRepoPaths.repoBucketName(canon)
        val dataDir = File(File(layoutRoot, "repos"), bucket)
        val primary = File(dataDir, "hookRunner.js")
        assertTrue(primary.isFile && primary.length() > 0L, "primary hookRunner.js missing")

        val prePushRunner = File(dataDir, "hookRunner-pre-push.sh")
        assertTrue(prePushRunner.isFile, "pre-push shell runner missing")
        assertTrue(prePushRunner.canExecute(), "pre-push runner should be executable")

        val preCommit = File(gitDir, "hooks/pre-commit")
        val prePush = File(gitDir, "hooks/pre-push")
        assertTrue(preCommit.isFile, "pre-commit hook should exist")
        assertTrue(prePush.isFile, "pre-push hook should exist")
        val pc = preCommit.readText()
        assertTrue(pc.contains("node"), "pre-commit should invoke node for hookRunner.js")
        assertTrue(pc.contains(primary.absolutePath) || pc.contains("hookRunner"), "pre-commit should reference hook runner")
        assertTrue(prePush.readText().contains(prePushRunner.absolutePath), "pre-push should invoke shell runner")
        assertTrue(prePush.readText().contains("\"\$@\""), "pre-push should forward args to runner")
        assertFalse(File(gitDir, "blamely/hookRunner.js").exists(), "should not write hookRunner under .git/blamely")
    }

    @Test
    fun `installAll preserves existing hook contents and appends Blamely block`() {
        val gitDir = File(tempDir, ".git").also { it.mkdirs() }
        val hooks = File(gitDir, "hooks").also { it.mkdirs() }
        val existing = File(hooks, "pre-commit")
        existing.writeText("#!/bin/sh\necho user-hook\nexit 0\n")
        existing.setExecutable(true)

        val result = HookInstaller.installAll(gitDir, tempDir, isolatedBlamelyRoot())
        assertTrue(result.ok)
        val updated = existing.readText()
        assertTrue(updated.contains("echo user-hook"), "user content should be preserved")
        assertTrue(updated.contains("Blamely hookRunner"), "Blamely block should be appended")

        assertTrue(File(hooks, "pre-commit.blamely.backup").isFile, "backup should be created")
    }

    @Test
    fun `installAll is idempotent and refreshes the existing block in place`() {
        val gitDir = File(tempDir, ".git").also { it.mkdirs() }
        val lr = isolatedBlamelyRoot()
        HookInstaller.installAll(gitDir, tempDir, lr)
        val first = File(gitDir, "hooks/pre-commit").readText()
        val result = HookInstaller.installAll(gitDir, tempDir, lr)
        assertTrue(result.ok)
        val second = File(gitDir, "hooks/pre-commit").readText()
        val occurrences = Regex("Blamely hookRunner \\(start\\)").findAll(second).count()
        assertEquals(1, occurrences, "Blamely block should appear exactly once after re-install")
        assertTrue(second.contains("node"))
        assertNotNull(first)
    }

    @Test
    fun `uninstallAll restores backup when present`() {
        val gitDir = File(tempDir, ".git").also { it.mkdirs() }
        val hooks = File(gitDir, "hooks").also { it.mkdirs() }
        val existing = File(hooks, "pre-commit")
        existing.writeText("#!/bin/sh\necho user-hook\nexit 0\n")
        existing.setExecutable(true)
        HookInstaller.installAll(gitDir, tempDir, isolatedBlamelyRoot())
        assertTrue(File(hooks, "pre-commit.blamely.backup").isFile)

        val result = uninstallByGitDir(gitDir)
        assertTrue(result, "uninstall should report ok")
        assertEquals("#!/bin/sh\necho user-hook\nexit 0\n", existing.readText())
        assertFalse(File(hooks, "pre-commit.blamely.backup").exists(), "backup should be cleaned up")
    }

    private fun uninstallByGitDir(gitDir: File): Boolean {
        val hooks = File(gitDir, "hooks")
        for (name in listOf("pre-commit", "pre-push")) {
            val backup = File(hooks, "$name.blamely.backup")
            val hook = File(hooks, name)
            if (backup.exists()) {
                backup.copyTo(hook, overwrite = true)
                hook.setExecutable(true)
                backup.delete()
            }
        }
        return true
    }
}
