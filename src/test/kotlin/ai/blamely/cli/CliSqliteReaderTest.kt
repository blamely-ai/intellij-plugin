package ai.blamely.cli

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

class CliSqliteReaderTest {

    @Test
    fun `loads edits for blamely-ci-test when db exists`() {
        val db = CliPaths.dbFile()
        assumeTrue(db.isFile, "no ~/.blamely/db.sqlite")
        val repo = "/Users/abdulkerimatik/development/training/blamely-ci-test"
        assumeTrue(File(repo).isDirectory)
        val head = ProcessBuilder("git", "-C", repo, "rev-parse", "HEAD")
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
        val branch = ProcessBuilder("git", "-C", repo, "symbolic-ref", "--short", "HEAD")
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
        val edits = CliSqliteReader.loadEditsForRepo(repo, branch, head)
        assertNotNull(edits, "loadEditsForRepo returned null (JDBC/SQL error)")
        assertTrue(edits!!.isNotEmpty(), "expected edits for $repo branch=$branch head=$head")
    }
}
