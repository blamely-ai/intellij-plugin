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

    /**
     * Debug logging is on when the `blamely.debug` system property is "true"
     * (set by ./run-sandbox.sh) OR the "Debug detection" setting is enabled.
     * Cached read of the system property; settings are read live.
     */
    fun isDebugEnabled(): Boolean {
        if (System.getProperty("blamely.debug") == "true") return true
        return try {
            ai.blamely.settings.BlamelySettings.getInstance().debugDetection
        } catch (_: Throwable) {
            false
        }
    }

    /** Logs at INFO level only when [isDebugEnabled]; safe to call on hot paths. */
    fun debug(message: String) {
        if (isDebugEnabled()) log.info(format("DEBUG", message))
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
