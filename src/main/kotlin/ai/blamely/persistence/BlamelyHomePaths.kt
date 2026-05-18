package ai.blamely.persistence

import java.io.File
import java.security.MessageDigest

/**
 * Legacy user-home layout for branch session lifecycle:
 * `~/.blamely/session/{repoKey}/{branchKey}/`.
 *
 * Kept for **read-only migration** into the new repo-local layout
 * (`.git/blamely/<branch>/{open,stash,closed}/`, see [BlamelyRepoPaths]).
 * Tests can override the home root with the `BLAMELY_SESSION_HOME` env var.
 *
 * [repoKey] disambiguates multiple clones; [branchKey] matches sanitized branch names.
 */
object BlamelyHomePaths {

    fun sessionRoot(): File = BlamelyRepoPaths.legacySessionRoot()

    fun repoKey(canonicalRepoRoot: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalRepoRoot.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun safeBranchName(branch: String?): String = BlamelyRepoPaths.safeBranchName(branch)

    fun branchDir(repoKey: String, branchKey: String): File =
        BlamelyRepoPaths.legacyBranchDir(repoKey, branchKey)

    fun openDir(repoKey: String, branchKey: String): File =
        BlamelyRepoPaths.legacyOpenDir(repoKey, branchKey)

    fun closedDir(repoKey: String, branchKey: String): File =
        BlamelyRepoPaths.legacyClosedDir(repoKey, branchKey)

    fun stashDir(repoKey: String, branchKey: String): File =
        BlamelyRepoPaths.legacyStashDir(repoKey, branchKey)
}
