package ai.blamely.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class HomeSessionSerializerTest {

    @Test
    fun moveOpenToClosed_updatesStatusAndCommit() {
        val tmp = Files.createTempDirectory("blamely-home-session").toFile()
        try {
            val openDir = File(tmp, "open")
            val closedDir = File(tmp, "closed")
            openDir.mkdirs()
            val session = HomeSessionSerializer.createNewSession("/repo/root", "main")
            val openFile = HomeSessionSerializer.newSessionFile(openDir, session.sessionId)
            HomeSessionSerializer.write(openFile, session)
            assertTrue(openFile.exists())

            val closedFile = HomeSessionSerializer.moveOpenToClosed(openFile, closedDir) { s ->
                s.commitSha = "deadbeef"
                s.commitNoteAttached = true
            }
            assertNotNull(closedFile)
            assertFalse(openFile.exists())
            val read = HomeSessionSerializer.read(closedFile!!)
            assertNotNull(read)
            assertEquals(HomeBranchSession.STATUS_CLOSED, read!!.status)
            assertEquals("deadbeef", read.commitSha)
            assertTrue(read.commitNoteAttached)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
