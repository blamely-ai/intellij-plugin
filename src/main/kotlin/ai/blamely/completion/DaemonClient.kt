package ai.blamely.completion

import ai.blamely.cli.CliPaths
import ai.blamely.utils.BlamelyLogger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

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

// DaemonClient posts attribution events to the blamely daemon's /edit endpoint.
// The daemon now uses a Unix domain socket at ~/.blamely/daemon.sock, which
// bypasses any network-level security tools that intercept localhost TCP.
// Falls back to the TCP port file for backward compat with old daemons.
class DaemonClient {
    @Volatile
    private var cachedPort: Int? = null

    @Volatile
    private var lastWarnAtMillis: Long = 0

    fun send(payload: EditPayload): Boolean {
        val sock = CliPaths.readDaemonSocket()
        return if (sock != null) {
            try {
                postViaSocket(sock, payload)
                true
            } catch (e: Exception) {
                maybeWarn("POST /edit via socket failed: ${e.message}")
                false
            }
        } else {
            val port = resolvePort()
            if (port == null) {
                maybeWarn("daemon socket/port file missing (blamely daemon not running?)")
                return false
            }
            try {
                postViaTCP(port, payload)
                true
            } catch (e: Exception) {
                cachedPort = null
                maybeWarn("POST /edit failed: ${e.message}")
                false
            }
        }
    }

    /**
     * Store the pre-apply file content in the daemon (PUT /snapshot) so the chat
     * watcher — and any narrowing that compares against a "before" baseline — has
     * the file as it looked BEFORE an AI agent rewrote it, including lines the
     * human typed since the last apply. Mirrors the VS Code plugin's
     * DaemonClient.putSnapshot. Fire-and-forget: best-effort, failures ignored.
     */
    fun putSnapshot(repoPath: String, filePath: String, content: String): Boolean {
        val body = encodeSnapshotJson(repoPath, filePath, content).toByteArray(Charsets.UTF_8)
        val sock = CliPaths.readDaemonSocket()
        return try {
            if (sock != null) {
                putViaSocket(sock, body)
            } else {
                val port = resolvePort() ?: return false
                putViaTCP(port, body)
            }
            true
        } catch (e: Exception) {
            maybeWarn("PUT /snapshot failed: ${e.message}")
            false
        }
    }

    private fun putViaSocket(sockPath: String, body: ByteArray) {
        val reqHead = buildString {
            append("PUT /snapshot HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        val addr = java.net.UnixDomainSocketAddress.of(sockPath)
        java.nio.channels.SocketChannel.open(addr).use { ch ->
            ch.configureBlocking(true)
            val buf = ByteBuffer.allocate(reqHead.size + body.size)
            buf.put(reqHead); buf.put(body); buf.flip()
            while (buf.hasRemaining()) ch.write(buf)
            val resp = ByteBuffer.allocate(128)
            ch.read(resp); resp.flip()
            val statusLine = Charsets.UTF_8.decode(resp).toString().split("\r\n").firstOrNull() ?: ""
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
            if (code != 204 && code != 200) throw RuntimeException("daemon returned $code")
        }
    }

    private fun putViaTCP(port: Int, body: ByteArray) {
        val conn = URL("http://127.0.0.1:$port/snapshot").openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout = 1500
        conn.requestMethod = "PUT"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Content-Length", body.size.toString())
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code != 204 && code != 200) throw RuntimeException("daemon returned $code")
        } finally {
            conn.disconnect()
        }
    }

    private fun resolvePort(): Int? {
        cachedPort?.let { return it }
        val p = CliPaths.readDaemonPort()
        if (p != null) cachedPort = p
        return p
    }

    private fun postViaSocket(sockPath: String, p: EditPayload) {
        val body = encodeJson(p).toByteArray(Charsets.UTF_8)
        val reqHead = buildString {
            append("POST /edit HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)

        val addr = java.net.UnixDomainSocketAddress.of(sockPath)
        java.nio.channels.SocketChannel.open(addr).use { ch ->
            ch.configureBlocking(true)
            val buf = ByteBuffer.allocate(reqHead.size + body.size)
            buf.put(reqHead)
            buf.put(body)
            buf.flip()
            while (buf.hasRemaining()) ch.write(buf)

            val resp = ByteBuffer.allocate(128)
            ch.read(resp)
            resp.flip()
            val statusLine = Charsets.UTF_8.decode(resp).toString().split("\r\n").firstOrNull() ?: ""
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
            if (code != 204) throw RuntimeException("daemon returned $code")
        }
    }

    private fun postViaTCP(port: Int, p: EditPayload) {
        val body = encodeJson(p).toByteArray(Charsets.UTF_8)
        val conn = URL("http://127.0.0.1:$port/edit").openConnection() as HttpURLConnection
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

// encodeSnapshotJson builds the {repo,file,content} body for PUT /snapshot.
private fun encodeSnapshotJson(repo: String, file: String, content: String): String =
    buildString {
        append('{')
        append("\"repo\":").append(quote(repo))
        append(",\"file\":").append(quote(file))
        append(",\"content\":").append(quote(content))
        append('}')
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
