package ai.blamely.cli

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

enum class CliHealthStatus {
    HEALTHY,
    NOT_INSTALLED,
    DAEMON_OFFLINE,
    GIT_HOOK_MISCONFIGURED,
}

data class CliHealthReport(
    val status: CliHealthStatus,
    val title: String,
    val message: String,
    val detail: String? = null,
    val installUrl: String = INSTALL_URL,
    val daemon: DaemonStatus = DaemonStatus(running = false),
)

private const val INSTALL_URL = "https://blamely.ai"

object CliHealth {
    private fun blamelyDirExists(): Boolean = CliPaths.blamelyHome().isDirectory

    private fun isCliInstalled(): Boolean {
        val home = CliPaths.blamelyHome()
        return File(home, "state.json").isFile
            || File(home, "bin${File.separator}blamely").isFile
            || File(home, "bin${File.separator}blamely.exe").isFile
            || CliPaths.daemonPortFile().isFile
            || CliPaths.daemonSocketFile().isFile
            || CliPaths.dbFile().isFile
    }

    private fun probeDaemon(): DaemonStatus {
        val sock = CliPaths.readDaemonSocket()
        if (sock != null) {
            return probeDaemonViaSocket(sock)
        }
        val port = CliPaths.readDaemonPort() ?: return DaemonStatus(running = false)
        return try {
            val conn = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.requestMethod = "GET"
            val ok = conn.responseCode == 200
            val body = conn.inputStream.bufferedReader().readText()
            DaemonStatus(running = ok && body.contains("\"ok\""), port = port)
        } catch (_: Exception) {
            DaemonStatus(running = false, port = port)
        }
    }

    private fun probeDaemonViaSocket(sockPath: String): DaemonStatus {
        return try {
            val req = "GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.UTF_8)
            val addr = java.net.UnixDomainSocketAddress.of(sockPath)
            java.nio.channels.SocketChannel.open(addr).use { ch ->
                ch.configureBlocking(true)
                ch.write(ByteBuffer.wrap(req))
                val resp = ByteBuffer.allocate(256)
                ch.read(resp)
                resp.flip()
                val text = Charsets.UTF_8.decode(resp).toString()
                val ok = text.startsWith("HTTP/1.1 200") && text.contains("\"ok\"")
                DaemonStatus(running = ok)
            }
        } catch (_: Exception) {
            DaemonStatus(running = false)
        }
    }

    private fun readGlobalGitHooksPath(): String? {
        return try {
            val pb = ProcessBuilder("git", "config", "--global", "core.hooksPath")
                .redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() != 0 || out.isBlank()) null else out
        } catch (_: Exception) {
            null
        }
    }

    private fun gitHookConfigured(hooksPath: String?): Boolean {
        if (hooksPath.isNullOrBlank()) return false
        val expected = CliPaths.gitHooksDir().absolutePath
        val norm = File(hooksPath).absolutePath
        if (norm == expected) return true
        val fwd = norm.replace('\\', '/')
        return fwd.endsWith("/.blamely/git-hooks") || fwd.endsWith("/git-hooks")
    }

    fun check(): CliHealthReport {
        if (!blamelyDirExists() || !isCliInstalled()) {
            return CliHealthReport(
                status = CliHealthStatus.NOT_INSTALLED,
                title = "Blamely CLI not installed",
                message = "This plugin reads attribution from the Blamely CLI. " +
                    "Install it from blamely.ai, then run `blamely install` in a terminal.",
                detail = "Without the CLI, runtime edits and commit reports will not be captured.",
                daemon = DaemonStatus(running = false),
            )
        }

        val daemon = probeDaemon()
        if (!daemon.running) {
            return CliHealthReport(
                status = CliHealthStatus.DAEMON_OFFLINE,
                title = "Blamely daemon offline",
                message = "The Blamely daemon is not responding. Run `blamely install` to re-register it, " +
                    "or inspect ~/.blamely/daemon.log for errors.",
                detail = if (daemon.port != null) {
                    "Port file exists (${daemon.port}) but /health did not respond."
                } else {
                    "No daemon.sock or daemon.port file — the daemon may never have started."
                },
                daemon = daemon,
            )
        }

        val hooksPath = readGlobalGitHooksPath()
        if (!gitHookConfigured(hooksPath)) {
            return CliHealthReport(
                status = CliHealthStatus.GIT_HOOK_MISCONFIGURED,
                title = "Blamely git hook not configured",
                message = "Global git core.hooksPath is not pointing at ~/.blamely/git-hooks. " +
                    "Run `blamely install` so commit reports are written to git notes.",
                detail = if (hooksPath.isNullOrBlank()) {
                    "core.hooksPath is not set."
                } else {
                    "Current core.hooksPath: $hooksPath"
                },
                daemon = daemon,
            )
        }

        return CliHealthReport(
            status = CliHealthStatus.HEALTHY,
            title = "Blamely CLI healthy",
            message = if (daemon.port != null) "Daemon running on port ${daemon.port}." else "Daemon running via Unix socket.",
            daemon = daemon,
        )
    }
}
