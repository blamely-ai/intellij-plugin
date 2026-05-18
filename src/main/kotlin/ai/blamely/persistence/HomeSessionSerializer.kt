package ai.blamely.persistence

import ai.blamely.utils.BlamelyLogger
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Read/write [HomeBranchSession] files under user home `.blamely/session/`.
 */
object HomeSessionSerializer {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun read(file: File): HomeBranchSession? {
        return try {
            if (!file.exists()) return null
            gson.fromJson(file.readText(), HomeBranchSession::class.java)
        } catch (e: Exception) {
            BlamelyLogger.warn("HomeSessionSerializer read failed: ${e.message}")
            null
        }
    }

    fun write(file: File, session: HomeBranchSession) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(session))
        } catch (e: Exception) {
            BlamelyLogger.error("HomeSessionSerializer write failed", e)
        }
    }

    /** Single open session file in [openDir], or null. */
    fun findOpenSessionFile(openDir: File): File? {
        if (!openDir.isDirectory) return null
        return openDir.listFiles()?.filter { it.name.endsWith(".json") }?.singleOrNull()
    }

    fun newSessionFile(openDir: File, sessionId: String): File =
        File(openDir, "$sessionId.json")

    fun closedSessionFile(closedDir: File, sessionId: String): File =
        File(closedDir, "$sessionId.json")

    fun nowIso(): String = Instant.now().toString()

    fun createNewSession(repoRoot: String, branch: String): HomeBranchSession {
        val id = UUID.randomUUID().toString()
        val t = nowIso()
        return HomeBranchSession(
            sessionId = id,
            repoRoot = repoRoot,
            branch = branch,
            status = HomeBranchSession.STATUS_OPEN,
            openedAt = t,
            updatedAt = t
        )
    }

    fun moveOpenToClosed(openFile: File, closedDir: File, updater: (HomeBranchSession) -> Unit): File? {
        val session = read(openFile) ?: return null
        updater(session)
        session.status = HomeBranchSession.STATUS_CLOSED
        if (session.closedAt.isNullOrBlank()) session.closedAt = nowIso()
        closedDir.mkdirs()
        val target = closedSessionFile(closedDir, session.sessionId)
        write(target, session)
        if (!openFile.delete()) {
            BlamelyLogger.warn("Could not delete open session file ${openFile.absolutePath}")
        }
        return target
    }
}
