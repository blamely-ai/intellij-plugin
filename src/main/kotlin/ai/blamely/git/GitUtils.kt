package ai.blamely.git

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.GitUtil
import ai.blamely.utils.Platform
import java.io.File

private val GIT_CANDIDATES = listOf(
    "git",
    "/usr/bin/git",
    "/usr/local/bin/git",
    "/opt/homebrew/bin/git"
)

/** Minimal git helpers for read-only oobeya-cli display. */
object GitUtils {

    fun run(project: Project, vararg args: String): String? {
        val cwd = project.basePath ?: return null
        return run(cwd, *args)
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
        } catch (_: Exception) {
            null
        }
    }

    data class DiffShortStat(val insertions: Int, val deletions: Int, val filesChanged: Int = 0)

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

    fun getWorkingTreeDiffShortStat(cwd: String): DiffShortStat {
        val out = run(cwd, "diff", "--shortstat", "HEAD") ?: return DiffShortStat(0, 0, 0)
        return parseDiffShortStat(out)
    }

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

    fun getWorkingTreeNumstatVsHead(cwd: String): Map<String, Pair<Int, Int>> {
        val out = run(cwd, "diff", "--numstat", "HEAD") ?: return emptyMap()
        return parseNumstat(out)
    }

    /**
     * Numstat for the current "session": working-tree diff if dirty,
     * otherwise falls back to the last commit diff (HEAD~1..HEAD) so data
     * remains visible after a commit on a clean working tree.
     */
    fun getSessionNumstat(cwd: String): Map<String, Pair<Int, Int>> {
        val wt = getWorkingTreeNumstatVsHead(cwd)
        if (wt.isNotEmpty()) return wt
        val out = run(cwd, "diff", "--numstat", "HEAD~1..HEAD") ?: return emptyMap()
        return parseNumstat(out)
    }

    private val repoRootCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun getRepoRoot(project: Project): String? {
        val basePath = project.basePath ?: return null
        repoRootCache[basePath]?.let { return it }
        val app = ApplicationManager.getApplication()
        if (!app.isWriteAccessAllowed) {
            try {
                val result = app.runReadAction<String?> {
                    val baseDir = File(basePath)
                    if (!baseDir.exists()) return@runReadAction null
                    val vf = LocalFileSystem.getInstance().findFileByIoFile(baseDir) ?: return@runReadAction basePath
                    GitUtil.getRepositoryManager(project).getRepositoryForFile(vf)?.root?.path ?: basePath
                }
                if (result != null) {
                    repoRootCache[basePath] = result
                    return result
                }
            } catch (_: Exception) {
            }
        }
        val root = run(basePath, "rev-parse", "--show-toplevel")?.trim() ?: basePath
        repoRootCache[basePath] = root
        return root
    }

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
        val root = run(cwd, "rev-parse", "--show-toplevel")?.trim() ?: cwd
        repoRootCache[path] = root
        return root
    }

    fun clearRepoRootCache() {
        repoRootCache.clear()
    }

    /** Path relative to git repo root (matches oobeya-cli / daemon `file_path` keys). */
    fun toRepoRelativePath(repoRoot: String, absolutePath: String): String? {
        if (repoRoot.isBlank() || absolutePath.isBlank()) return null
        return try {
            val root = File(repoRoot).canonicalFile
            val file = File(absolutePath).canonicalFile
            val rel = file.relativeToOrNull(root)?.path ?: return null
            if (rel.isEmpty() || rel.startsWith("..")) null
            else Platform.normalizePath(rel)
        } catch (_: Exception) {
            null
        }
    }

    fun getBranch(project: Project): String? {
        val cwd = project.basePath ?: return null
        return run(cwd, "rev-parse", "--abbrev-ref", "HEAD")
    }

    /**
     * Short name of the checked-out branch for the repo at [cwd], or null when
     * HEAD is detached. Used to tag edits with their branch-based work session;
     * symbolic-ref (unlike --abbrev-ref) fails on detached HEAD rather than
     * returning the literal "HEAD".
     */
    fun getBranchName(cwd: String): String? =
        run(cwd, "symbolic-ref", "--quiet", "--short", "HEAD")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Reports whether the repo at [cwd] is mid-way through a history-rewriting
     * operation (cherry-pick, merge, revert, rebase). Edits the editor observes
     * during these are replays of existing content, not fresh authorship, so the
     * detectors pause recording while one is in progress.
     */
    fun inProgressGitOp(cwd: String): Boolean {
        val gitDir = run(cwd, "rev-parse", "--absolute-git-dir")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return false
        val dir = java.io.File(gitDir)
        return listOf("CHERRY_PICK_HEAD", "MERGE_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply")
            .any { java.io.File(dir, it).exists() }
    }

    fun getNoteContent(cwd: String, sha: String): String? =
        run(cwd, "notes", "--ref=blamely", "show", sha)
}
