package ai.blamely.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RepoSessionSerializerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `write then read preserves session data`() {
        val openDir = File(tempDir, "open").also { it.mkdirs() }
        val session = HomeSessionSerializer.createNewSession("/repo/root", "feature/x")
        val file = RepoSessionSerializer.newSessionFile(openDir, session.sessionId)
        assertTrue(RepoSessionSerializer.write(file, session))
        val read = RepoSessionSerializer.read(file)
        assertNotNull(read)
        assertEquals(session.sessionId, read!!.sessionId)
        assertEquals("feature/x", read.branch)
        assertEquals(HomeBranchSession.STATUS_OPEN, read.status)
    }

    @Test
    fun `findOpenSessionFile returns the only json file`() {
        val openDir = File(tempDir, "open").also { it.mkdirs() }
        File(openDir, "ignore.txt").writeText("noise")
        val session = HomeSessionSerializer.createNewSession("/r", "main")
        val file = RepoSessionSerializer.newSessionFile(openDir, session.sessionId)
        RepoSessionSerializer.write(file, session)
        assertEquals(file, RepoSessionSerializer.findOpenSessionFile(openDir))
    }

    @Test
    fun `moveOpenToClosed moves and updates status and commit metadata`() {
        val openDir = File(tempDir, "open").also { it.mkdirs() }
        val closedDir = File(tempDir, "closed")
        val session = HomeSessionSerializer.createNewSession("/r", "main")
        val openFile = RepoSessionSerializer.newSessionFile(openDir, session.sessionId)
        RepoSessionSerializer.write(openFile, session)

        val closedFile = RepoSessionSerializer.moveOpenToClosed(openFile, closedDir) { s ->
            s.commitSha = "abc12345"
            s.commitNoteAttached = true
        }
        assertNotNull(closedFile)
        assertFalse(openFile.exists(), "open file should be removed")
        val read = RepoSessionSerializer.read(closedFile!!)
        assertNotNull(read)
        assertEquals(HomeBranchSession.STATUS_CLOSED, read!!.status)
        assertEquals("abc12345", read.commitSha)
        assertTrue(read.commitNoteAttached)
        assertNotNull(read.closedAt)
    }

    @Test
    fun `writeTrackedMirror writes blamely json under intellij home layout`() {
        val ijHome = File(tempDir, "ij-home").also { it.mkdirs() }
        System.setProperty("blamely.intellij.home", ijHome.absolutePath)
        try {
            val repo = File(tempDir, "repo").also { it.mkdirs() }
            val session = HomeSessionSerializer.createNewSession(repo.absolutePath, "main")
            assertTrue(RepoSessionSerializer.writeTrackedMirror(repo, session))
            val canon = repo.canonicalPath
            val target = BlamelyRepoPaths.intellijTrackedSessionFile(canon, session.sessionId)
            assertTrue(target.exists(), "tracked mirror should exist at ${target.absolutePath}")
            val read = RepoSessionSerializer.read(target)
            assertNotNull(read)
            assertEquals(session.sessionId, read!!.sessionId)
        } finally {
            System.clearProperty("blamely.intellij.home")
        }
    }
}
