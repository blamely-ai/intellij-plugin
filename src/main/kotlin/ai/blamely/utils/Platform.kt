package ai.blamely.utils

/**
 * Platform and path utilities (mirrors VS Code Platform.ts).
 */
object Platform {

    /** File inside `<git-dir>/blamely/` consumed by `hookRunner.js` (VS Code parity). */
    const val BLAMELY_REPO_DETECTOR_FILENAME = "blamely-detector.ai"

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
}
