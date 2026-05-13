package ai.blamely.git

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.GitUtil
import ai.blamely.utils.Platform
import java.io.File

/** Common git executable paths when "git" is not in PATH (e.g. IDE launcher). */
private val GIT_CANDIDATES = listOf(
    "git",
    "/usr/bin/git",
    "/usr/local/bin/git",
    "/opt/homebrew/bin/git"
)

/**
 * Git helpers: branch, HEAD sha, .git/blamely path, git notes.
 * Mirrors Blamely VS Code GitUtils for same .git/blamely layout.
 */
object GitUtils {

    fun run(project: Project, vararg args: String): String? {
        return run(project.basePath ?: return null, *args)
    }

    fun run(cwd: String, vararg args: String): String? {
        for (gitExe in GIT_CANDIDATES) {
            if (gitExe != "git" && !File(gitExe).canExecute()) continue
            val out = runWithGit(cwd, gitExe, *args)
            if (out != null) return out
        }
        return null
    }

    private fun runWithGit(cwd: String, gitExe: String, vararg args: String): String? {
        return try {
            val pb = ProcessBuilder(gitExe, *args)
                .directory(File(cwd))
                .redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() != 0) return null
            out.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    /** Runs git command; returns true if exit code is 0 (success). Use for commands that produce no useful stdout (e.g. notes add). */
    fun runSuccess(project: Project, vararg args: String): Boolean {
        val cwd = project.basePath ?: return false
        return runSuccess(cwd, *args)
    }

    fun runSuccess(cwd: String, vararg args: String): Boolean {
        for (gitExe in GIT_CANDIDATES) {
            if (gitExe != "git" && !File(gitExe).canExecute()) continue
            if (runSuccessWithGit(cwd, gitExe, *args)) return true
        }
        return false
    }

    private fun runSuccessWithGit(cwd: String, gitExe: String, vararg args: String): Boolean {
        return try {
            val pb = ProcessBuilder(gitExe, *args)
                .directory(File(cwd))
                .redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.bufferedReader().readText()
            p.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun isGitRepo(project: Project): Boolean {
        val cwd = project.basePath ?: return false
        return run(cwd, "rev-parse", "--is-inside-work-tree") == "true"
    }

    /** True if there are uncommitted changes (modified/added/deleted files) in the working tree. */
    fun hasUncommittedChanges(cwd: String): Boolean {
        val out = run(cwd, "status", "--porcelain") ?: return false
        return out.isNotBlank()
    }

    fun hasUncommittedChanges(project: Project): Boolean {
        val cwd = getRepoRoot(project) ?: project.basePath ?: return false
        return hasUncommittedChanges(cwd)
    }

    /** Summary line from `git diff --shortstat HEAD` (insertions/deletions vs HEAD). */
    data class DiffShortStat(val insertions: Int, val deletions: Int, val filesChanged: Int = 0)

    /** Parses `git diff --shortstat` output, e.g. ` 3 files changed, 25 insertions(+), 10 deletions(-)`. */
    fun parseDiffShortStat(line: String): DiffShortStat {
        val t = line.trim()
        if (t.isEmpty()) return DiffShortStat(0, 0, 0)
        var files = 0
        Regex("(\\d+) files? changed").find(t)?.let { files = it.groupValues[1].toInt() }
        var ins = 0
        Regex("(\\d+) insertions?").find(t)?.let { ins = it.groupValues[1].toInt() }
        var del = 0
        Regex("(\\d+) deletions?").find(t)?.let { del = it.groupValues[1].toInt() }
        return DiffShortStat(ins, del, files)
    }

    /** Working tree vs `HEAD`: total insertions and deletions (empty diff → zeros). */
    fun getWorkingTreeDiffShortStat(cwd: String): DiffShortStat {
        val out = run(cwd, "diff", "--shortstat", "HEAD") ?: return DiffShortStat(0, 0, 0)
        return parseDiffShortStat(out)
    }

    /** Parses `git diff --numstat` lines: `added<TAB>removed<TAB>path` (skips binary `-`). */
    fun parseNumstat(output: String): Map<String, Pair<Int, Int>> {
        val map = linkedMapOf<String, Pair<Int, Int>>()
        for (line in output.lines()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val addStr = parts[0].trim()
            val delStr = parts[1].trim()
            val path = Platform.normalizePath(parts.drop(2).joinToString("\t").trim())
            if (path.isEmpty() || addStr == "-" || delStr == "-") continue
            val a = addStr.toIntOrNull() ?: continue
            val d = delStr.toIntOrNull() ?: continue
            map[path] = a to d
        }
        return map
    }

    /** Per-path insert/delete vs HEAD for paths changed in the working tree. */
    fun getWorkingTreeNumstatVsHead(cwd: String): Map<String, Pair<Int, Int>> {
        val out = run(cwd, "diff", "--numstat", "HEAD") ?: return emptyMap()
        return parseNumstat(out)
    }

    private val repoRootCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Repo root for running git. Uses a cache after first resolution.
     * Avoids runReadAction when a write lock is pending or held to prevent
     * deadlocks during IDE startup or heavy plugin activity.
     */
    fun getRepoRoot(project: Project): String? {
        val basePath = project.basePath ?: return null
        repoRootCache[basePath]?.let { return it }

        val app = ApplicationManager.getApplication()
        // Try Git4Idea only when safe — skip if a write lock is already held (would deadlock)
        if (!app.isWriteAccessAllowed) {
            try {
                val result = app.runReadAction<String?> {
                    val baseDir = File(basePath)
                    if (!baseDir.exists()) return@runReadAction null
                    val vf = LocalFileSystem.getInstance().findFileByIoFile(baseDir)
                        ?: return@runReadAction basePath
                    val repo = GitUtil.getRepositoryManager(project).getRepositoryForFile(vf)
                        ?: return@runReadAction basePath
                    repo.root.path
                }
                if (result != null) {
                    repoRootCache[basePath] = result
                    return result
                }
            } catch (_: Exception) {
                // ReadAction failed (e.g. deadlock avoidance), fall through to CLI
            }
        }

        // CLI fallback: never needs a read action lock
        val cliRoot = run(basePath, "rev-parse", "--show-toplevel")?.trim()
        val root = cliRoot ?: basePath
        repoRootCache[basePath] = root
        return root
    }

    /**
     * Repo root for a path on disk. Accepts either a file or a directory; for files we use
     * the parent directory as the git cwd. Previously, callers passed `document.uri.fsPath`
     * directly which made Git fail (file is not a directory) and silently broke session
     * folder creation. Returns null when no git repo can be located.
     */
    fun getRepoRoot(path: String): String? {
        if (path.isBlank()) return null
        repoRootCache[path]?.let { return it }
        val raw = File(path)
        val target = when {
            !raw.exists() -> raw
            raw.isDirectory -> raw
            else -> raw.parentFile ?: return null
        }
        val cwd = target.absolutePath
        val cliRoot = run(cwd, "rev-parse", "--show-toplevel")?.trim()
        val resolved = cliRoot ?: cwd
        repoRootCache[path] = resolved
        return resolved
    }

    fun clearRepoRootCache() {
        repoRootCache.clear()
    }

    fun getLatestCommitSha(project: Project): String? {
        val cwd = getRepoRoot(project) ?: project.basePath ?: return null
        return run(cwd, "rev-parse", "HEAD")
    }

    /** Paths (relative to repo root, forward slashes) of files changed in the given commit. */
    fun getFilesChangedInCommit(cwd: String, commitSha: String): List<String> {
        var out = run(cwd, "diff-tree", "--no-commit-id", "--name-only", "-r", commitSha)
        if (out.isNullOrBlank()) {
            val hasParent = run(cwd, "rev-parse", "$commitSha^") != null
            if (!hasParent) out = run(cwd, "ls-tree", "-r", "--name-only", commitSha)
        }
        if (out.isNullOrBlank()) return emptyList()
        return out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.replace('\\', '/') }
            .toList()
    }

    /**
     * Returns 1-based line numbers of lines added in the given commit for the given file (repo-relative path).
     * Parses `git show <sha> -p -- <file>` unified diff. Returns empty list if file not in commit or parse error.
     */
    fun getAddedLineNumbersInCommit(cwd: String, commitSha: String, filePathRepoRelative: String): List<Int> {
        return getDiffStats(cwd, commitSha, filePathRepoRelative).addedLines
    }

    data class FileDiffStats(
        val addedLines: List<Int>,
        val deletedLines: List<Int>,
        val addedCount: Int,
        val deletedCount: Int
    )

    /**
     * Parses a unified diff patch (e.g. `git show` / `git diff --cached`) into added / deleted 1-based line numbers.
     * Added lines use NEW file numbering; deleted lines use OLD file numbering.
     */
    fun parseUnifiedDiff(patch: String): FileDiffStats {
        val added = mutableListOf<Int>()
        val deleted = mutableListOf<Int>()
        var currentNewLine = 0
        var currentOldLine = 0
        for (line in patch.lineSequence()) {
            when {
                line.startsWith("@@") -> {
                    val match = Regex("@@ -(\\d+)(,\\d+)? \\+(\\d+)(,(\\d+))? @@").find(line) ?: continue
                    val g = match.groupValues
                    currentOldLine = g.getOrNull(1)?.toIntOrNull() ?: 0
                    currentNewLine = g.getOrNull(3)?.toIntOrNull() ?: 0
                }
                line.startsWith("+") && !line.startsWith("+++") -> {
                    added.add(currentNewLine)
                    currentNewLine++
                }
                line.startsWith("-") && !line.startsWith("---") -> {
                    deleted.add(currentOldLine)
                    currentOldLine++
                }
                line.startsWith(" ") -> {
                    currentNewLine++
                    currentOldLine++
                }
            }
        }
        return FileDiffStats(added, deleted, added.size, deleted.size)
    }

    /**
     * Parses the unified diff for a file in a commit and returns both added and deleted line numbers.
     * Added lines are numbered in the NEW file; deleted lines are numbered in the OLD file.
     */
    fun getDiffStats(cwd: String, commitSha: String, filePathRepoRelative: String): FileDiffStats {
        val out = run(cwd, "show", commitSha, "-p", "--", filePathRepoRelative)
            ?: return FileDiffStats(emptyList(), emptyList(), 0, 0)
        return parseUnifiedDiff(out)
    }

    /** Repo-relative paths with staged changes (`git diff --cached --name-only`). */
    fun listStagedRepoRelativePaths(cwd: String): List<String> {
        val out = run(cwd, "diff", "--cached", "--name-only") ?: return emptyList()
        return out.lineSequence()
            .map { Platform.normalizePath(it.trim()) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    /** Staged patch for one file (index vs HEAD). */
    fun getStagedDiffStats(cwd: String, filePathRepoRelative: String): FileDiffStats {
        val out = run(cwd, "diff", "--cached", "-p", "--", filePathRepoRelative)
            ?: return FileDiffStats(emptyList(), emptyList(), 0, 0)
        return parseUnifiedDiff(out)
    }

    /**
     * Converts repo-relative paths to project-relative so they match blame map keys.
     * When project.basePath == repoRoot, returns paths as-is. When project is under repo, strips the prefix.
     */
    fun repoRelativeToProjectRelative(repoRoot: String, basePath: String?, repoRelativePaths: Collection<String>): Set<String> {
        if (basePath == null || basePath.isBlank()) return repoRelativePaths.toSet()
        val normRepo = repoRoot.replace('\\', '/').trimEnd('/')
        val normBase = basePath.replace('\\', '/').trimEnd('/')
        if (normBase == normRepo) return repoRelativePaths.map { it.replace('\\', '/') }.toSet()
        val prefix = normBase.removePrefix(normRepo).trimStart('/')
        if (prefix.isEmpty()) return repoRelativePaths.map { it.replace('\\', '/') }.toSet()
        val prefixWithSlash = "$prefix/"
        return repoRelativePaths
            .map { it.replace('\\', '/') }
            .filter { it == prefix || it.startsWith(prefixWithSlash) }
            .map { if (it == prefix) "" else it.removePrefix(prefixWithSlash) }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun getShortSha(project: Project): String? {
        val cwd = project.basePath ?: return null
        return run(cwd, "rev-parse", "--short=8", "HEAD")
    }

    fun getBranch(project: Project): String? {
        val cwd = project.basePath ?: return null
        return run(cwd, "rev-parse", "--abbrev-ref", "HEAD")
    }

    /** Branch for a repo at [cwd] (directory). Returns null when not a git repo. */
    fun getBranchForCwd(cwd: String): String? =
        run(cwd, "rev-parse", "--abbrev-ref", "HEAD")

    fun getCommitMessage(project: Project): String? {
        val cwd = project.basePath ?: return null
        return run(cwd, "log", "-1", "--pretty=%B")
    }

    fun getGitDir(project: Project): String? {
        val cwd = project.basePath ?: return null
        return getGitDirForCwd(cwd)
    }

    /** Resolve `$GIT_DIR` for repo at [cwd] (directory). Returns null when not a git repo. */
    fun getGitDirForCwd(cwd: String): String? {
        return run(cwd, "rev-parse", "--git-dir")?.let { rel ->
            val candidate = File(rel)
            val resolved = if (candidate.isAbsolute) candidate else File(cwd, rel)
            resolved.absolutePath
        }
    }

    /** Path to .git/blamely (or $GIT_DIR/blamely). */
    fun getBlamelyDir(project: Project): File? {
        val gitDir = getGitDir(project) ?: return null
        return File(gitDir, "blamely")
    }

    fun getBlamelyDir(cwd: String): File? {
        val gitDir = getGitDirForCwd(cwd) ?: return null
        return File(gitDir, "blamely")
    }

    /**
     * Run git and return (exitCode, stdout, stderr). For diagnosing failures.
     */
    fun runWithStderr(cwd: String, vararg args: String): Triple<Int, String, String> {
        var last = Triple(-1, "", "git not found")
        for (gitExe in GIT_CANDIDATES) {
            if (gitExe != "git" && !File(gitExe).exists()) continue
            val r = runWithStderrWithGit(cwd, gitExe, *args)
            if (r.first >= 0) return r // process ran (exit 0 or not)
            last = r
        }
        return last
    }

    private fun runWithStderrWithGit(cwd: String, gitExe: String, vararg args: String): Triple<Int, String, String> {
        return try {
            val pb = ProcessBuilder(gitExe, *args)
                .directory(File(cwd))
                .redirectErrorStream(false)
            val p = pb.start()
            val stdout = p.inputStream.bufferedReader().readText().trim()
            val stderr = p.errorStream.bufferedReader().readText().trim()
            p.waitFor()
            Triple(p.exitValue(), stdout, stderr)
        } catch (e: Exception) {
            Triple(-1, "", e.message ?: e.toString())
        }
    }

    /** Outcome of [addGitNoteWithResult]. [ok] = true means the note was written. */
    data class NoteWriteResult(
        val ok: Boolean,
        val exitCode: Int,
        val stderr: String,
        val stdout: String
    ) {
        /** Short, human-readable description suitable for notifications. */
        fun describe(sha: String): String {
            if (ok) return "Git note attached for ${sha.take(8)}."
            val tail = when {
                stderr.isNotBlank() -> stderr.lineSequence().firstOrNull { it.isNotBlank() } ?: stderr
                stdout.isNotBlank() -> stdout.lineSequence().firstOrNull { it.isNotBlank() } ?: stdout
                else -> "exit=$exitCode"
            }
            return "Failed to attach git note for ${sha.take(8)}: $tail"
        }
    }

    /** Add a git note for commit [sha] with [content] in repo at [cwd]. Use this from tests or when cwd is known. */
    fun addGitNote(cwd: String, sha: String, content: String): Boolean =
        addGitNoteWithResult(cwd, sha, content).ok

    /** Add a git note and return a structured result including stderr/stdout for surfacing to the user. */
    fun addGitNoteWithResult(cwd: String, sha: String, content: String): NoteWriteResult {
        val tmp = java.io.File.createTempFile("blamely-note-", ".txt")
        return try {
            tmp.writeText(content)
            val (code, out, err) = runWithStderr(cwd, "notes", "--ref=blamely", "add", "-F", tmp.absolutePath, "-f", sha)
            if (code != 0) {
                com.intellij.openapi.diagnostic.Logger.getInstance("Blamely.GitUtils")
                    .warn("git notes add failed (exit=$code) stderr: $err stdout: $out")
            }
            NoteWriteResult(ok = code == 0, exitCode = code, stderr = err, stdout = out)
        } finally {
            tmp.delete()
        }
    }

    fun addGitNote(project: Project, sha: String, content: String): Boolean {
        val cwd = project.basePath ?: return false
        return addGitNoteWithResult(cwd, sha, content).ok
    }

    /** Read note body for [sha] from repo at [cwd], or null if no note or error. Useful for tests. */
    fun getNoteContent(cwd: String, sha: String): String? {
        return run(cwd, "notes", "--ref=blamely", "show", sha)
    }

    /**
     * Returns true if [sha] looks like a commit made locally (authored within the last [thresholdMs]).
     * Used to distinguish local commits from commits arriving via pull/fetch.
     */
    fun isLocalCommit(cwd: String, sha: String, thresholdMs: Long = 30_000): Boolean {
        val authorEpoch = run(cwd, "log", "-1", "--format=%at", sha) ?: return false
        val commitEpochSec = authorEpoch.trim().toLongOrNull() ?: return false
        val nowSec = System.currentTimeMillis() / 1000
        return (nowSec - commitEpochSec) < (thresholdMs / 1000)
    }

    fun pushGitNotes(cwd: String): Boolean {
        val remotes = run(cwd, "remote") ?: return false
        if (remotes.isBlank()) return false
        return runSuccess(cwd, "push", "origin", "refs/notes/blamely")
    }

    fun pushGitNotes(project: Project): Boolean {
        val cwd = project.basePath ?: return false
        return pushGitNotes(cwd)
    }

    /** OID of current stash tip (`refs/stash`), or null if no stash. */
    fun getRefsStashSha(cwd: String): String? {
        val (code, out, _) = runWithStderr(cwd, "rev-parse", "refs/stash")
        if (code != 0 || out.isBlank()) return null
        return out.trim().lines().firstOrNull()?.trim()?.takeIf { it.length >= 7 }
    }

    data class StashEntry(
        val stashRef: String,
        val stashSha: String,
        val message: String?
    )

    /**
     * Parses `git stash list` with `%gd` (ref), `%H` (commit), `%gs` (subject) per entry (3 lines each).
     */
    fun listStashEntries(cwd: String): List<StashEntry> {
        val format = "%gd%n%H%n%gs%n"
        val (code, out, _) = runWithStderr(cwd, "stash", "list", "--format=$format")
        if (code != 0) return emptyList()
        val lines = out.trim().lines().filter { it.isNotBlank() }
        val result = mutableListOf<StashEntry>()
        var i = 0
        while (i + 2 < lines.size) {
            val ref = lines[i].trim()
            val sha = lines[i + 1].trim()
            val msg = lines[i + 2].trim()
            if (ref.startsWith("stash@{") && sha.length >= 7) {
                result.add(StashEntry(ref, sha, msg.ifBlank { null }))
            }
            i += 3
        }
        return result
    }

    /** Top stash entry (most recent), if any. */
    fun getTopStashEntry(cwd: String): StashEntry? = listStashEntries(cwd).firstOrNull()
}
