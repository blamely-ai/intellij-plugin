package ai.blamely.cli

import java.io.File

object CliPaths {
    fun blamelyHome(): File {
        System.getenv("BLAMELY_HOME")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return File(it)
        }
        System.getenv("BLAMELY_DATA_HOME")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return File(it)
        }
        return File(System.getProperty("user.home"), ".blamely")
    }

    fun dbFile(): File = File(blamelyHome(), "db.sqlite")

    fun daemonPortFile(): File = File(blamelyHome(), "daemon.port")

    fun daemonSocketFile(): File = File(blamelyHome(), "daemon.sock")

    fun stateFile(): File = File(blamelyHome(), "state.json")

    fun installedBinary(): File {
        val name = if (System.getProperty("os.name").lowercase().contains("win")) "blamely.exe" else "blamely"
        return File(blamelyHome(), "bin${File.separator}$name")
    }

    fun gitHooksDir(): File = File(blamelyHome(), "git-hooks")

    fun readDaemonPort(): Int? {
        return try {
            val n = daemonPortFile().readText().trim().toIntOrNull()
            if (n != null && n > 0) n else null
        } catch (_: Exception) {
            null
        }
    }

    /** Returns the socket path if the socket file exists (daemon running), otherwise null.
     *  The socket file must NOT be read — it is a Unix domain socket, not a plain file. */
    fun readDaemonSocket(): String? {
        return try {
            val f = daemonSocketFile()
            if (f.exists()) f.absolutePath else null
        } catch (_: Exception) {
            null
        }
    }
}
