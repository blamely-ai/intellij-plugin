// Working-log + baseline storage — Kotlin port of internal/authorship/store.go and
// the TS store. Plain files under .git/blamely so the IDE and the CLI share one
// working log with no daemon/DB. Same layout, sanitization, atomic temp+rename, and
// portable O_EXCL lockfile. Cross-platform (java.nio + path joins).
//
// The on-disk JSON MUST match the Go/TS format exactly (snake_case keys, lowercase
// author) — the three write the same files — so serialization goes through the wire
// DTOs below, not the domain data classes directly.
package ai.blamely.authorship

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

object WorkingLogStore {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    private const val LOCK_TIMEOUT_MS = 5_000L
    private const val LOCK_STALE_MS = 10_000L
    private const val LOCK_POLL_MS = 15L

    // ── paths (match Go/TS) ──────────────────────────────────────────────────

    fun sanitizeComponent(s: String): String {
        if (s.isEmpty()) return "_"
        val b = StringBuilder()
        for (c in s) {
            when {
                c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' ||
                    c == '"' || c == '<' || c == '>' || c == '|' || c < ' ' -> b.append('-')
                else -> b.append(c)
            }
        }
        return b.toString()
    }

    fun cleanRel(rel: String): String =
        rel.replace('\\', '/').removePrefix("./").trimStart('/')

    private fun workingLogDir(repoRoot: String, branch: String, baseSha: String): File =
        File(File(File(File(repoRoot, ".git"), "blamely"), "working_logs"),
            sanitizeComponent(branch) + File.separator + sanitizeComponent(baseSha))

    fun workingLogPath(repoRoot: String, branch: String, baseSha: String, relPath: String): File =
        File(workingLogDir(repoRoot, branch, baseSha), cleanRel(relPath).replace('/', File.separatorChar) + ".json")

    fun baselinePath(repoRoot: String, branch: String, baseSha: String, relPath: String): File =
        File(File(workingLogDir(repoRoot, branch, baseSha), ".baselines"),
            cleanRel(relPath).replace('/', File.separatorChar))

    // ── load / save ──────────────────────────────────────────────────────────

    fun loadWorkingLog(repoRoot: String, branch: String, baseSha: String, relPath: String): WorkingLog? {
        val f = workingLogPath(repoRoot, branch, baseSha, relPath)
        if (!f.exists()) return null
        return try {
            gson.fromJson(f.readText(), WlWire::class.java).toDomain()
        } catch (_: Exception) {
            null
        }
    }

    /** save persists an already-computed working log + its content baseline (the
     *  editor tracker holds the log in memory and applies edits per change; this
     *  writes the latest state on flush). Locked + atomic, mirrors store.go save. */
    fun save(repoRoot: String, branch: String, baseSha: String, relPath: String, wl: WorkingLog, content: String) {
        val rel = cleanRel(relPath)
        val wlPath = workingLogPath(repoRoot, branch, baseSha, relPath)
        val basePath = baselinePath(repoRoot, branch, baseSha, relPath)
        withFileLock(wlPath) {
            val wire = WlWire.fromDomain(wl.copy(file = rel, baseSha = baseSha), sha256Hex(content))
            atomicWrite(wlPath, gson.toJson(wire))
            atomicWrite(basePath, content)
        }
    }

    // ── primitives ─────────────────────────────────────────────────────────--

    private fun atomicWrite(target: File, data: String) {
        target.parentFile?.mkdirs()
        val tmp = File.createTempFile(".wl-", ".tmp", target.parentFile)
        try {
            tmp.writeText(data)
            try {
                Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete() // harmless once the move succeeded
        }
    }

    private fun <T> withFileLock(target: File, fn: () -> T): T {
        val lock = File(target.parentFile, target.name + ".lock")
        lock.parentFile?.mkdirs()
        val deadline = System.currentTimeMillis() + LOCK_TIMEOUT_MS
        while (true) {
            val created = try {
                Files.createFile(lock.toPath()); true
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                false
            }
            if (created) {
                try {
                    return fn()
                } finally {
                    lock.delete()
                }
            }
            if (lock.exists() && System.currentTimeMillis() - lock.lastModified() > LOCK_STALE_MS) {
                lock.delete() // orphaned lock from a crashed writer — steal it
                continue
            }
            if (System.currentTimeMillis() > deadline) {
                throw RuntimeException("authorship: timed out acquiring lock ${lock.path}")
            }
            Thread.sleep(LOCK_POLL_MS)
        }
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ── wire format (matches Go/TS JSON exactly) ───────────────────────────────

    private data class WlWire(
        val schema: String = WORKING_LOG_SCHEMA,
        val file: String = "",
        @SerializedName("base_sha") val baseSha: String = "",
        @SerializedName("blob_sha") val blobSha: String = "",
        @SerializedName("updated_ms") val updatedMs: Long = 0,
        val lines: List<LineWire> = emptyList(),
    ) {
        fun toDomain(): WorkingLog = WorkingLog(
            schema = schema, file = file, baseSha = baseSha,
            lines = lines.map {
                LineAttribution(it.start, it.end,
                    Author(AuthorType.fromWire(it.author), it.tool ?: "", it.model ?: "", it.genType ?: "", it.session ?: ""))
            },
        )

        companion object {
            fun fromDomain(wl: WorkingLog, blobSha: String): WlWire = WlWire(
                schema = wl.schema.ifEmpty { WORKING_LOG_SCHEMA },
                file = wl.file, baseSha = wl.baseSha, blobSha = blobSha,
                updatedMs = System.currentTimeMillis(),
                lines = wl.lines.map { la ->
                    LineWire(
                        la.start, la.end, la.author.type.wire,
                        la.author.tool.ifEmpty { null }, la.author.model.ifEmpty { null },
                        la.author.genType.ifEmpty { null }, la.author.session.ifEmpty { null },
                    )
                },
            )
        }
    }

    private data class LineWire(
        val start: Int,
        val end: Int,
        val author: String,
        val tool: String? = null,
        val model: String? = null,
        @SerializedName("gen_type") val genType: String? = null,
        val session: String? = null,
    )
}
