package ai.blamely.completion

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DaemonClientWorktreeTest {
    @Test fun `edit JSON keeps canonical identity and carries the active checkout`() {
        val p = EditPayload(tool = "copilot", repoPath = "/main", worktreePath = "/linked worktree",
            filePath = "file.txt", branch = "feature/worktree", lines = listOf(EditRange(1, 1)))
        val json = JsonParser.parseString(encodeJson(p)).asJsonObject
        assertEquals("/main", json["repo_path"].asString)
        assertEquals("/linked worktree", json["worktree_path"].asString)
        assertEquals("feature/worktree", json["branch"].asString)
        assertFalse(JsonParser.parseString(encodeJson(p.copy(worktreePath = null))).asJsonObject.has("worktree_path"))
    }

    @Test fun `snapshot JSON uses distinct checkout keys for the same file`() {
        val main = JsonParser.parseString(encodeSnapshotJson("/main", "file.txt", "main baseline\n")).asJsonObject
        val linked = JsonParser.parseString(encodeSnapshotJson("/linked", "file.txt", "linked baseline\n")).asJsonObject
        assertNotEquals(main["repo"], linked["repo"])
        assertEquals("/linked", linked["repo"].asString)
        assertEquals("linked baseline\n", linked["content"].asString)
        assertEquals(main["file"], linked["file"])
    }
}
