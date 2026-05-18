package ai.blamely.persistence

import java.io.File

/**
 * Repo-local layout (Blamely 1.x): per-branch working state lives inside the repo
 * git directory (`.git/blamely`).
 *
 * - `.git/blamely/<sanitized-branch>/open/<sessionId>.json`  — active sessions
 * - `.git/blamely/<sanitized-branch>/stash/<stashSha>.json`  — stash links
 * - `.git/blamely/<sanitized-branch>/closed/<sessionId>.json`— closed sessions
 * - `.git/blamely/<sanitized-branch>/trace/`                 — trace store
 * - `.git/blamely/<sanitized-branch>/report.yml`             — per-branch report
 * - `.git/blamely/snapshots/<sanitized-branch>/`             — line-blame snapshots
 *
 * **IntelliJ plugin:** session mirror files are **not** written under the workspace.
 * They live under **`~/.blamely/intellij/repos/<repo-name>/sessions/<id>/blamely.json`**.
 * Override with env **`BLAMELY_INTELLIJ_HOME`** or system property **`blamely.intellij.home`** (tests).
 *
 * Legacy workspace mirror (VS Code parity), still readable for optional migration copy:
 *
 * - `<repo>/blamely/sessions/<sessionId>/blamely.json`
 *
 * Legacy locations that are still **read** for migration:
 *
 * - `~/.blamely/session/<repoKey>/<branchKey>/{open,stash,closed}/`
 * - `.git/blamely/snapshots/<file>` (flat, no per-branch subdir)
 *
 * Tests can override the legacy session home with the `BLAMELY_SESSION_HOME`
 * environment variable so they do not pollute the real `$HOME/.blamely`.
 */
object BlamelyRepoPaths {

    private const val SESSIONS_TRACKED_DIR = "blamely/sessions"
    private const val INTELLIJ_ROOT = ".blamely/intellij"
    private const val INTELLIJ_HOME_ENV = "BLAMELY_INTELLIJ_HOME"
    private const val INTELLIJ_HOME_PROP = "blamely.intellij.home"
    private const val LEGACY_HOME_ROOT = ".blamely/session"
    private const val LEGACY_HOME_ENV = "BLAMELY_SESSION_HOME"

    /** Sanitize a branch name into a path-safe segment (matches VS Code `safeBranchName`). */
    fun safeBranchName(branch: String?): String {
        val b = branch?.takeIf { it.isNotBlank() } ?: "HEAD"
        val s = b
            .replace('/', '-')
            .replace('\\', '-')
            .replace(':', '-')
            .replace('*', '-')
            .replace('?', '-')
            .replace('"', '-')
            .replace('<', '-')
            .replace('>', '-')
            .replace('|', '-')
            .ifBlank { "HEAD" }
        return if (s == "." || s == "..") "HEAD" else s
    }

    /** Root of repo-local working state (e.g. `.git/blamely`). */
    fun blamelyDir(gitDir: File): File = File(gitDir, "blamely")

    /** Per-branch working dir, e.g. `.git/blamely/<branch>`. */
    fun branchDir(gitDir: File, branch: String?): File =
        File(blamelyDir(gitDir), safeBranchName(branch))

    fun openDir(gitDir: File, branch: String?): File = File(branchDir(gitDir, branch), "open")
    fun stashDir(gitDir: File, branch: String?): File = File(branchDir(gitDir, branch), "stash")
    fun closedDir(gitDir: File, branch: String?): File = File(branchDir(gitDir, branch), "closed")
    fun traceDir(gitDir: File, branch: String?): File = File(branchDir(gitDir, branch), "trace")

    /** Per-branch `report.yml` (alongside the existing root report). */
    fun reportFile(gitDir: File, branch: String?): File = File(branchDir(gitDir, branch), "report.yml")

    /** Per-branch blame snapshot directory: `.git/blamely/snapshots/<branch>/`. */
    fun snapshotsDir(gitDir: File, branch: String?): File =
        File(File(blamelyDir(gitDir), "snapshots"), safeBranchName(branch))

    /** Root for IntelliJ-only session mirrors: `~/.blamely/intellij` unless overridden. */
    fun intellijDataRoot(): File {
        System.getenv(INTELLIJ_HOME_ENV)?.trim()?.takeIf { it.isNotEmpty() }?.let { return File(it) }
        System.getProperty(INTELLIJ_HOME_PROP)?.trim()?.takeIf { it.isNotEmpty() }?.let { return File(it) }
        val home = System.getProperty("user.home") ?: "."
        return File(File(home), INTELLIJ_ROOT)
    }

    /** `~/.blamely/intellij/repos/<repo-name>/sessions/` for [canonicalRepoRoot]. */
    fun intellijTrackedSessionsDir(canonicalRepoRoot: String): File {
        val seg = BlamelyUserRepoPaths.repoBucketName(canonicalRepoRoot)
        return File(File(File(intellijDataRoot(), "repos"), seg), "sessions")
    }

    /** IntelliJ session mirror file (outside workspace). */
    fun intellijTrackedSessionFile(canonicalRepoRoot: String, sessionId: String): File =
        File(File(intellijTrackedSessionsDir(canonicalRepoRoot), sessionId), "blamely.json")

    /** VS Code-style workspace mirror — legacy; used only for one-time copy when migrating. */
    fun workspaceTrackedSessionsDir(repoRoot: File): File = File(repoRoot, SESSIONS_TRACKED_DIR)

    fun workspaceTrackedSessionFile(repoRoot: File, sessionId: String): File =
        File(File(workspaceTrackedSessionsDir(repoRoot), sessionId), "blamely.json")

    /**
     * Legacy `~/.blamely/session/` root. Honors `BLAMELY_SESSION_HOME` so tests don't
     * touch the real user home. Returns the directory whether or not it exists.
     */
    fun legacySessionRoot(): File {
        val override = System.getenv(LEGACY_HOME_ENV)
        if (!override.isNullOrBlank()) return File(override)
        val home = System.getProperty("user.home") ?: "."
        return File(File(home), LEGACY_HOME_ROOT)
    }

    /** Legacy per-repo-per-branch dir under `~/.blamely/session/<repoKey>/<branchKey>/`. */
    fun legacyBranchDir(repoKey: String, branchKey: String): File =
        File(File(legacySessionRoot(), repoKey), branchKey)

    fun legacyOpenDir(repoKey: String, branchKey: String): File =
        File(legacyBranchDir(repoKey, branchKey), "open")

    fun legacyClosedDir(repoKey: String, branchKey: String): File =
        File(legacyBranchDir(repoKey, branchKey), "closed")

    fun legacyStashDir(repoKey: String, branchKey: String): File =
        File(legacyBranchDir(repoKey, branchKey), "stash")
}
