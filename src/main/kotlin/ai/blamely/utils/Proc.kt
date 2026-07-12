package ai.blamely.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Hardened subprocess runner for CLI helpers (`blamely authorship`, git).
 *
 * The plain `ProcessBuilder(...).inputStream.readText() + waitFor()` pattern has
 * no timeout: a hung child blocks the pooled refresh thread forever, and because
 * CliDataService serializes refreshes behind an AtomicBoolean, every later
 * refresh is deferred too — the gutter never repopulates. The VS Code plugin
 * kills the child at 15–20 s and moves on; this gives the Kotlin side the same
 * behavior (plus an output cap so a runaway child can't exhaust memory).
 */
internal object Proc {
    /**
     * Runs [cmd] (optionally in [dir]) and returns its trimmed stdout, or null
     * on non-zero exit, timeout, oversized output, or any launch failure.
     * stderr is discarded (prevents a full stderr pipe from deadlocking the
     * child without needing a drain thread).
     */
    fun run(cmd: List<String>, dir: File? = null, timeoutMs: Long, maxBytes: Int): String? {
        var proc: Process? = null
        return try {
            val pb = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD)
            if (dir != null) pb.directory(dir)
            val p = pb.start()
            proc = p
            p.outputStream.close() // no stdin

            // Read stdout on a dedicated thread so waitFor(timeout) below stays
            // authoritative even if the child writes slowly or not at all.
            val buf = ByteArrayOutputStream()
            var overflow = false
            val reader = Thread({
                try {
                    val chunk = ByteArray(64 * 1024)
                    val ins = p.inputStream
                    while (true) {
                        val n = ins.read(chunk)
                        if (n < 0) break
                        if (buf.size() + n > maxBytes) {
                            overflow = true
                            p.destroyForcibly()
                            break
                        }
                        buf.write(chunk, 0, n)
                    }
                } catch (_: Exception) {
                    // stream closed by destroy — fine
                }
            }, "blamely-proc-reader")
            reader.isDaemon = true
            reader.start()

            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                p.waitFor(2, TimeUnit.SECONDS)
                BlamelyLogger.warn("Proc: timed out after ${timeoutMs}ms: ${cmd.joinToString(" ")}")
                return null
            }
            reader.join(1000)
            if (overflow) {
                BlamelyLogger.warn("Proc: output exceeded $maxBytes bytes: ${cmd.joinToString(" ")}")
                return null
            }
            if (p.exitValue() != 0) return null
            buf.toString(Charsets.UTF_8).trim()
        } catch (_: Exception) {
            proc?.destroyForcibly()
            null
        }
    }
}
