package ai.blamely.completion

/**
 * Path filtering shared by the detectors (CompletionDetector's file-create
 * handler and AgentEditDetector's VFS pipeline): build artifacts, VCS/IDE
 * metadata, and dependency dirs are never AI-authored source.
 */
internal object DetectorPaths {
    /** Skip pathologically large files — hashing every line would be wasteful. */
    const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private val EXCLUDED_DIRS = setOf(
        ".git", ".idea", "build", "out", "target", "dist", "node_modules", ".gradle",
    )

    fun isExcluded(absPath: String): Boolean {
        val p = absPath.replace('\\', '/')
        return EXCLUDED_DIRS.any { p.contains("/$it/") }
    }
}
