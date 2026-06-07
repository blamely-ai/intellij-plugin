package ai.blamely.completion

import ai.blamely.cli.CliPaths
import ai.blamely.utils.BlamelyLogger
import java.net.HttpURLConnection
import java.net.URL

// EditPayload mirrors daemon.EditPayload (Go side). Field names match the
// JSON tags expected by /edit on the blamely daemon.
data class EditRange(val start: Int, val end: Int, val contentSha: String? = null)

data class EditPayload(
    val tool: String,
    val confidence: String? = null,
    val genType: String? = null,
    val repoPath: String,
    val filePath: String,
    val model: String? = null,
    val suggestedLines: Long = 0,
    val lines: List<EditRange>,
    val rawMeta: String? = null,
    // Branch the editor was on when the edit was made. The daemon scopes
    // attribution by branch-based work session; if empty it resolves from repo.
    val branch: String? = null,
)

// FsEventPayload mirrors daemon.FsEventPayload (Go side).
data class FsEventPayload(
    val kind: String,          // delete | create | rename | copy
    val repoPath: String,
    val path: String? = null,     // delete / create
    val oldPath: String? = null,  // rename
    val newPath: String? = null,  // rename
    val srcPath: String? = null,  // copy
    val dstPath: String? = null,  // copy
)

// DaemonClient posts attribution events to the blamely daemon's /edit and /fs
// endpoints. The port is read from ~/.blamely/daemon.port; on any failure
// the cache is invalidated so a daemon restart (with a new port) heals
// on the next call.
class DaemonClient {
    @Volatile
    private var cachedPort: Int? = null

    @Volatile
    private var lastWarnAtMillis: Long = 0

    fun send(payload: EditPayload): Boolean {
        val port = resolvePort()
        if (port == null) {
            maybeWarn("daemon port file missing (blamely daemon not running?)")
            return false
        }
        return try {
            postRaw(port, "/edit", encodeJson(payload))
            true
        } catch (e: Exception) {
            cachedPort = null
            maybeWarn("POST /edit failed: ${e.message}")
            false
        }
    }

    fun sendFsEvent(payload: FsEventPayload): Boolean {
        val port = resolvePort() ?: return false // daemon not running — silently skip
        return try {
            postRaw(port, "/fs", encodeFsJson(payload))
            true
        } catch (e: Exception) {
            cachedPort = null
            maybeWarn("POST /fs failed: ${e.message}")
            false
        }
    }

    private fun resolvePort(): Int? {
        cachedPort?.let { return it }
        val p = CliPaths.readDaemonPort()
        if (p != null) cachedPort = p
        return p
    }

    private fun postRaw(port: Int, endpoint: String, json: String) {
        val body = json.toByteArray(Charsets.UTF_8)
        val conn = URL("http://127.0.0.1:$port$endpoint").openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout = 1500
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Content-Length", body.size.toString())
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code != 204) {
                throw RuntimeException("daemon returned $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun maybeWarn(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastWarnAtMillis < 30_000) return
        lastWarnAtMillis = now
        BlamelyLogger.warn("DaemonClient: $msg")
    }
}

// encodeJson hand-rolls the JSON envelope so the plugin doesn't grow a
// dependency on a JSON library for this one POST. The shape is small and
// fully under our control; only string-escaping needs care.
private fun encodeJson(p: EditPayload): String {
    val sb = StringBuilder()
    sb.append('{')
    sb.append("\"tool\":").append(quote(p.tool))
    p.confidence?.let { sb.append(",\"confidence\":").append(quote(it)) }
    p.genType?.let { sb.append(",\"gen_type\":").append(quote(it)) }
    sb.append(",\"repo_path\":").append(quote(p.repoPath))
    sb.append(",\"file_path\":").append(quote(p.filePath))
    p.model?.let { sb.append(",\"model\":").append(quote(it)) }
    if (p.suggestedLines > 0) {
        sb.append(",\"suggested_lines\":").append(p.suggestedLines)
    }
    sb.append(",\"lines\":[")
    p.lines.forEachIndexed { i, r ->
        if (i > 0) sb.append(',')
        sb.append('{')
        sb.append("\"start\":").append(r.start)
        sb.append(",\"end\":").append(r.end)
        r.contentSha?.let { sb.append(",\"content_sha\":").append(quote(it)) }
        sb.append('}')
    }
    sb.append(']')
    p.rawMeta?.let { sb.append(",\"raw_meta\":").append(quote(it)) }
    p.branch?.takeIf { it.isNotEmpty() }?.let { sb.append(",\"branch\":").append(quote(it)) }
    sb.append('}')
    return sb.toString()
}

private fun encodeFsJson(p: FsEventPayload): String {
    val sb = StringBuilder()
    sb.append('{')
    sb.append("\"kind\":").append(quote(p.kind))
    sb.append(",\"repo_path\":").append(quote(p.repoPath))
    p.path?.let { sb.append(",\"path\":").append(quote(it)) }
    p.oldPath?.let { sb.append(",\"old_path\":").append(quote(it)) }
    p.newPath?.let { sb.append(",\"new_path\":").append(quote(it)) }
    p.srcPath?.let { sb.append(",\"src_path\":").append(quote(it)) }
    p.dstPath?.let { sb.append(",\"dst_path\":").append(quote(it)) }
    sb.append('}')
    return sb.toString()
}

private fun quote(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') {
                sb.append(String.format("\\u%04x", c.code))
            } else {
                sb.append(c)
            }
        }
    }
    sb.append('"')
    return sb.toString()
}
