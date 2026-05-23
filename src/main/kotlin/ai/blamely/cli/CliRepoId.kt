package ai.blamely.cli

import ai.blamely.git.GitUtils
import java.io.File

object CliRepoId {
    /**
     * Canonical repo identity — matches oobeya-cli gitutil.RepoID.
     */
    fun get(repoRoot: String): String? {
        val gitDir = GitUtils.run(repoRoot, "rev-parse", "--path-format=absolute", "--git-common-dir")
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var dir = File(gitDir)
        if (dir.name == ".git") {
            dir = dir.parentFile ?: return null
        }
        return try {
            dir.canonicalPath
        } catch (_: Exception) {
            dir.absolutePath
        }
    }
}
