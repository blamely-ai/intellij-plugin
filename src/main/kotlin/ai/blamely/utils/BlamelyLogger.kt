package ai.blamely.utils

import com.intellij.openapi.diagnostic.Logger
import java.time.Instant

/**
 * Central logger for Blamely (mirrors VS Code Logger.ts format: level + ISO timestamp).
 * Delegates to IntelliJ Logger; prefix helps filter in IDE log.
 */
object BlamelyLogger {
    private const val PREFIX = "Blamely"
    private val log = Logger.getInstance(PREFIX)

    private fun format(level: String, message: String, err: Throwable? = null): String {
        val ts = Instant.now().toString()
        val suffix = err?.message?.let { ": $it" } ?: ""
        return "[$level $ts] $message$suffix"
    }

    fun info(message: String) {
        log.info(format("INFO", message))
    }

    fun warn(message: String) {
        log.warn(format("WARN", message))
    }

    fun error(message: String, err: Throwable? = null) {
        if (err != null) log.error(format("ERROR", message, err), err)
        else log.error(format("ERROR", message))
    }
}
