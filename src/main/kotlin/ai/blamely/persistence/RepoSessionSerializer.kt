package ai.blamely.persistence

import ai.blamely.utils.BlamelyLogger
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * Read/write [HomeBranchSession] files under the repo-local layout
 * (`.git/blamely/<branch>/{open,stash,closed}/`) plus IntelliJ mirror under
 * `~/.blamely/intellij/repos/<repoKey>/sessions/<id>/blamely.json` (see [BlamelyRepoPaths.intellijTrackedSessionFile]).
 *
 * Reuses [HomeBranchSession] as the on-disk model so a single record can be migrated
 * from `~/.blamely/session/` to `.git/blamely/<branch>/` without conversion.
 */
object RepoSessionSerializer {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun read(file: File): HomeBranchSession? {
        return try {
            if (!file.exists()) return null
            gson.fromJson(file.readText(), HomeBranchSession::class.java)
        } catch (e: Exception) {
            BlamelyLogger.warn("RepoSessionSerializer read failed: ${e.message}")
            null
        }
    }

    /**
     * Best-effort write. Catches stash / git / IO errors so they cannot block the
     * higher-level "create or update session" code path. Returns true on success.
     */
    fun write(file: File, session: HomeBranchSession): Boolean {
        return try {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(session))
            true
        } catch (e: Exception) {
            BlamelyLogger.warn("RepoSessionSerializer write failed for ${file.absolutePath}: ${e.message}")
            false
        }
    }

    /** Single open session file in [openDir], or null. */
    fun findOpenSessionFile(openDir: File): File? {
        if (!openDir.isDirectory) return null
        return openDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".json") }
    }

    fun newSessionFile(openDir: File, sessionId: String): File =
        File(openDir, "$sessionId.json")

    fun closedSessionFile(closedDir: File, sessionId: String): File =
        File(closedDir, "$sessionId.json")

    fun moveOpenToClosed(openFile: File, closedDir: File, updater: (HomeBranchSession) -> Unit): File? {
        val session = read(openFile) ?: return null
        updater(session)
        session.status = HomeBranchSession.STATUS_CLOSED
        if (session.closedAt.isNullOrBlank()) session.closedAt = HomeSessionSerializer.nowIso()
        closedDir.mkdirs()
        val target = closedSessionFile(closedDir, session.sessionId)
        write(target, session)
        if (openFile.exists() && !openFile.delete()) {
            BlamelyLogger.warn("Could not delete open session file ${openFile.absolutePath}")
        }
        return target
    }

    /**
     * Session mirror outside the workspace: `~/.blamely/intellij/repos/<repoKey>/sessions/<id>/blamely.json`.
     * If a legacy workspace file exists at `<repo>/blamely/sessions/...` and the new path is missing,
     * it is copied once before writing (migration).
     */
    fun writeTrackedMirror(repoRoot: File, session: HomeBranchSession): Boolean {
        val canon = try {
            repoRoot.canonicalPath
        } catch (_: Exception) {
            repoRoot.absolutePath
        }
        val target = BlamelyRepoPaths.intellijTrackedSessionFile(canon, session.sessionId)
        val legacy = BlamelyRepoPaths.workspaceTrackedSessionFile(repoRoot, session.sessionId)
        if (!target.exists() && legacy.exists()) {
            try {
                legacy.copyTo(target, overwrite = false)
            } catch (_: Exception) {
            }
        }
        return write(target, session)
    }
}
