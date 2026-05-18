package ai.blamely.utils

/**
 * Platform and path utilities (mirrors VS Code Platform.ts).
 */
object Platform {

    private val osName: String by lazy {
        System.getProperty("os.name", "").lowercase()
    }

    fun isWindows(): Boolean = osName.contains("win")
    fun isMac(): Boolean = osName.contains("mac")
    fun isLinux(): Boolean = osName.contains("linux") || osName.contains("nix")

    /**
     * Normalize path to use forward slashes (e.g. for .git/blamely keys).
     */
    fun normalizePath(filePath: String): String =
        filePath.replace('\\', '/')

    /**
     * Encode path for use in filenames (e.g. replace / and \ with __).
     */
    fun encodeFilePath(relativePath: String): String =
        relativePath.replace(Regex("[/\\\\]"), "__")

    /**
     * Decode path from encoded form.
     */
    fun decodeFilePath(encoded: String): String =
        encoded.replace("__", "/")

    /**
     * If [pathStr] is a persisted blame sidecar under a /snapshots/ segment with a .blame.json
     * filename, returns the decoded source blame key; otherwise null.
     */
    fun blameKeyFromSnapshotSidecarPath(pathStr: String): String? {
        val norm = normalizePath(pathStr)
        val parts = norm.split('/').filter { it.isNotEmpty() }
        var snapIdx = -1
        for (i in parts.indices) {
            if (parts[i] == "snapshots") snapIdx = i
        }
        if (snapIdx < 0 || snapIdx + 1 >= parts.size) return null
        val before = parts.subList(0, snapIdx)
        var last = parts.last()
        var low = last.lowercase()
        val suf = ".blame.json"
        while (low.endsWith(suf)) {
            last = last.substring(0, last.length - suf.length)
            low = last.lowercase()
        }
        if (last.isEmpty()) return null
        var decoded = decodeFilePath(last)
        var out = normalizePath(decoded.replace('\\', '/'))
        val outLow = out.lowercase()
        if (outLow.contains("/.blamely/") && outLow.contains("/snapshots/")) {
            out = java.io.File(out.replace('\\', '/')).name
        }
        return if (before.size == 1 && before[0] != "logs") {
            normalizePath("${before[0]}/$out")
        } else {
            normalizePath(out)
        }
    }

    /**
     * Stable key for *.blame.json persistence — never a snapshot sidecar path or *.blame.json key
     * (avoids double `.blame.json` filenames when a snapshot file is edited).
     */
    fun normalizeBlamePersistenceKey(filePath: String, projectBasePath: String?): String {
        blameKeyFromSnapshotSidecarPath(filePath)?.let { return it }
        if (projectBasePath != null) {
            val base = projectBasePath.trimEnd('/', '\\')
            val rel = when {
                filePath == base -> ""
                filePath.startsWith("$base/") -> filePath.substring(base.length + 1)
                filePath.startsWith("$base\\") -> filePath.substring(base.length + 1).replace('\\', '/')
                else -> null
            }
            if (!rel.isNullOrEmpty()) {
                blameKeyFromSnapshotSidecarPath(rel)?.let { return it }
            }
        }
        var k = normalizePath(filePath)
        val suf = ".blame.json"
        while (k.endsWith(suf, ignoreCase = true)) {
            k = k.substring(0, k.length - suf.length)
        }
        return k
    }
}
