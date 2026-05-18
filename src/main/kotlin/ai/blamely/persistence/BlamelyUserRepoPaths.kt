package ai.blamely.persistence

import ai.blamely.git.GitUtils
import ai.blamely.utils.BlamelyLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * User layout under ~/.blamely/repos/<repo-name>/ — matches blamely-cli and VS Code:
 * - `branches/<branch>/trace/session.json` + `report.yml` — blamely-cli (ai_cli_trace)
 * - Legacy hash bucket: `repos/<64-hex>/` (migration source; `cli-traces/<uuid>/` still read from there)
 * - `branches/<branch>/` — open|stash|closed, trace/ide-inline data, report.yml
 * - `snapshots/<branch>/` — working-tree blame JSON (pre-commit)
 * - `logs/commits/<sha>/snapshots/` + `report.yml` — blame + report archived at commit
 */
object BlamelyUserRepoPaths {

    private val RESERVED_TOP_LEVEL = setOf(
        "cli-traces", "snapshots", "branches", "sessions", "logs",
        "hookRunner.js", "hookRunner-pre-push.sh", "blamely-detector.ai"
    )

    /** Human-readable ~/.blamely/repos/<segment>/ (sanitized git root basename; matches blamely-cli `RepoBucketName`). */
    fun repoBucketName(canonicalRepoRoot: String): String {
        val f = File(canonicalRepoRoot)
        val base = f.name
        if (base.isEmpty() || base == "." || base == ".." || base == File.separator) {
            return "repo-" + blamelyRepoKey64(canonicalRepoRoot).take(8)
        }
        val s = BlamelyRepoPaths.safeBranchName(base)
        if (s.isEmpty() || s == "HEAD" || s == "." || s == "..") {
            return "repo-" + blamelyRepoKey64(canonicalRepoRoot).take(8)
        }
        if (s in RESERVED_TOP_LEVEL) {
            return "repo-$s"
        }
        return s
    }

    fun blamelyUserLayoutRoot(): File {
        System.getenv("BLAMELY_HOME")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return File(it).canonicalFile
        }
        System.getenv("BLAMELY_DATA_HOME")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return File(it).canonicalFile
        }
        val home = System.getProperty("user.home") ?: "."
        return File(home, ".blamely")
    }

    /** Canonical absolute path; resolves symlinks when possible (matches `realpath`). */
    fun canonicalRepoDiskPath(repoRoot: String): String {
        return try {
            File(repoRoot).canonicalFile.absolutePath
        } catch (_: Exception) {
            File(repoRoot).normalize().absolutePath
        }
    }

    private fun normalizePathForRepoKey(canonicalRepoRoot: String): String {
        var s = canonicalRepoRoot.replace('\\', '/')
        if (s.length >= 2 && s[1] == ':') {
            s = s[0].lowercaseChar() + s.substring(2)
        }
        return s
    }

    /** Full 64-char hex repo id — matches blamely-cli `blamelydir.repoKey` and VS Code `cliTraceRepoKey`. */
    fun blamelyRepoKey64(canonicalRepoRoot: String): String {
        val normalized = normalizePathForRepoKey(canonicalRepoRoot)
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** First 8 hex chars (legacy bucket / display). */
    fun blamelyRepoStableId8(canonicalRepoRoot: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalRepoRoot.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(8)
    }

    fun userBlamelyReposRoot(userLayoutRoot: File = blamelyUserLayoutRoot()): File =
        File(userLayoutRoot, "repos")

    fun cliTraceParentDir(repoRoot: File, userLayoutRoot: File = blamelyUserLayoutRoot()): File {
        val canon = canonicalRepoDiskPath(repoRoot.absolutePath)
        val key = blamelyRepoKey64(canon)
        return File(File(userBlamelyReposRoot(userLayoutRoot), key), "cli-traces")
    }

    /**
     * Resolves `~/.blamely/repos/<repo-name>/` for [repoRoot], migrating legacy 8-char and 64-hex buckets
     * and copying from `.git/blamely` when present (never deletes the git-dir copy).
     */
    fun resolveBlamelyDataDir(repoRoot: File, userLayoutRoot: File = blamelyUserLayoutRoot()): File? {
        val canon = canonicalRepoDiskPath(repoRoot.absolutePath)
        val rootFile = File(canon)
        if (!rootFile.isDirectory) return null
        val reposRoot = userBlamelyReposRoot(userLayoutRoot)
        reposRoot.mkdirs()
        val key64 = blamelyRepoKey64(canon)
        var dir64 = File(reposRoot, key64)
        val key8 = blamelyRepoStableId8(canon)
        val dir8 = File(reposRoot, key8)
        try {
            if (!dir64.exists() && dir8.exists()) {
                Files.move(dir8.toPath(), dir64.toPath(), StandardCopyOption.ATOMIC_MOVE)
                BlamelyLogger.info("Blamely: migrated repo bucket 8-char -> 64-char under ~/.blamely/repos")
            } else if (dir64.exists() && dir8.exists() && dir64.absolutePath != dir8.absolutePath) {
                dir8.copyRecursively(dir64, overwrite = false)
            }
        } catch (e: Exception) {
            BlamelyLogger.warn("Blamely: repo bucket merge skipped: ${e.message}")
            dir64 = File(reposRoot, key64)
            dir64.mkdirs()
        }
        if (!dir64.exists()) dir64.mkdirs()

        val nameDir = File(reposRoot, repoBucketName(canon))
        try {
            if (!nameDir.exists() && dir64.exists() && nameDir.absolutePath != dir64.absolutePath) {
                dir64.copyRecursively(nameDir, overwrite = false)
            }
        } catch (e: Exception) {
            BlamelyLogger.warn("Blamely: named repo bucket copy skipped: ${e.message}")
        }
        if (!nameDir.exists()) nameDir.mkdirs()

        normalizeBranchesLayout(nameDir)
        copyFromGitBlamelyIfPresent(rootFile, nameDir)
        return nameDir
    }

    /** ~/.blamely/repos/<repo-name>/branches/<sanitized-branch>/ */
    fun branchWorkDir(repoRoot: File, branch: String?, userLayoutRoot: File = blamelyUserLayoutRoot()): File? {
        val data = resolveBlamelyDataDir(repoRoot, userLayoutRoot) ?: return null
        return File(File(data, "branches"), BlamelyRepoPaths.safeBranchName(branch))
    }

    /** ~/.blamely/repos/<repo-name>/branches/<branch>/report.yml */
    fun reportYamlFile(repoRoot: File, branch: String?, userLayoutRoot: File = blamelyUserLayoutRoot()): File? {
        val bw = branchWorkDir(repoRoot, branch, userLayoutRoot) ?: return null
        return File(bw, "report.yml")
    }

    /** ~/.blamely/repos/<repo-name>/snapshots/<branch>/ */
    fun blameSnapshotsDir(repoRoot: File, branch: String?, userLayoutRoot: File = blamelyUserLayoutRoot()): File? {
        val data = resolveBlamelyDataDir(repoRoot, userLayoutRoot) ?: return null
        return File(File(data, "snapshots"), BlamelyRepoPaths.safeBranchName(branch))
    }

    fun legacyFlatSessionSnapshotsDir(repoRoot: File, branch: String?): File {
        val canon = canonicalRepoDiskPath(repoRoot.absolutePath)
        val key = "${blamelyRepoStableId8(canon)}_${BlamelyRepoPaths.safeBranchName(branch)}"
        return File(File(BlamelyRepoPaths.legacySessionRoot(), key), "snapshots")
    }

    private fun looksLikeBranchFolder(f: File): Boolean {
        if (!f.isDirectory) return false
        return File(f, "open").exists() ||
            File(f, "trace").exists() ||
            File(f, "report.yml").exists() ||
            File(f, "closed").exists() ||
            File(f, "stash").exists()
    }

    /** Promote ~/.blamely/repos/<key>/<branch>/ to ~/.blamely/repos/<key>/branches/<branch>/. */
    private fun normalizeBranchesLayout(dataDir: File) {
        val branchesRoot = File(dataDir, "branches")
        if (branchesRoot.isDirectory) return
        val children = dataDir.listFiles() ?: return
        val toMove = children.filter {
            it.isDirectory &&
                it.name !in RESERVED_TOP_LEVEL &&
                looksLikeBranchFolder(it)
        }
        if (toMove.isEmpty()) return
        branchesRoot.mkdirs()
        for (src in toMove) {
            val dest = File(branchesRoot, src.name)
            if (dest.exists()) continue
            try {
                Files.move(src.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                src.copyRecursively(dest, overwrite = false)
                src.deleteRecursively()
            }
        }
    }

    private fun copyFromGitBlamelyIfPresent(repoRoot: File, dataDir: File) {
        try {
            val gitDirPath = GitUtils.getGitDirForCwd(repoRoot.absolutePath) ?: return
            val gitBlamely = File(gitDirPath, "blamely")
            if (!gitBlamely.isDirectory) return
            val branchesRoot = File(dataDir, "branches").also { it.mkdirs() }
            val snapRoot = File(dataDir, "snapshots").also { it.mkdirs() }
            for (child in gitBlamely.listFiles().orEmpty()) {
                if (child.name == "snapshots" && child.isDirectory) {
                    child.listFiles()?.forEach { b ->
                        if (b.isDirectory) {
                            b.copyRecursively(File(snapRoot, b.name), overwrite = false)
                        }
                    }
                    continue
                }
                if (child.isDirectory && looksLikeBranchFolder(child)) {
                    child.copyRecursively(File(branchesRoot, child.name), overwrite = false)
                }
            }
        } catch (e: Exception) {
            BlamelyLogger.warn("Blamely: copy from .git/blamely skipped: ${e.message}")
        }
    }

    /** No-op: IDE does not persist under `branches/<branch>/trace/`. */
    fun archiveBranchTraceToClosed(
        @Suppress("UNUSED_PARAMETER") repoRoot: File,
        @Suppress("UNUSED_PARAMETER") branch: String?,
        @Suppress("UNUSED_PARAMETER") headShaFull: String,
        @Suppress("UNUSED_PARAMETER") userLayoutRoot: File = blamelyUserLayoutRoot()
    ) {
        return
    }

    /** Archives blame snapshots into logs/commits/<sha>/snapshots/ (VS Code / blamely-cli parity). */
    fun archiveBranchBlameSnapshotsToClosed(
        repoRoot: File,
        branch: String?,
        headShaFull: String,
        userLayoutRoot: File = blamelyUserLayoutRoot()
    ) {
        if (System.getenv("BLAMELY_ARCHIVE_TRACE_ON_COMMIT")?.trim() == "0") return
        val tip = headShaFull.trim()
        if (tip.isEmpty()) return
        val data = resolveBlamelyDataDir(repoRoot, userLayoutRoot) ?: return
        val bk = BlamelyRepoPaths.safeBranchName(branch)
        val snapDir = File(File(data, "snapshots"), bk)
        if (!snapDir.isDirectory) return
        val files = snapDir.listFiles()?.filter { it.isFile && it.name.endsWith(".blame.json") } ?: return
        if (files.isEmpty()) return
        val destDir = closedCommitSnapshotsDir(repoRoot, branch, tip, userLayoutRoot) ?: return
        destDir.mkdirs()
        for (f in files) {
            val t = File(destDir, f.name)
            if (t.exists()) continue
            try {
                f.copyTo(t, overwrite = false)
                f.delete()
            } catch (e: Exception) {
                BlamelyLogger.warn("Blamely: archive snapshot ${f.name}: ${e.message}")
            }
        }
    }

    /**
     * Copy archived per-commit .blame.json files from logs/commits/(sha)/snapshots/ back to
     * snapshots/(branch)/ after post-commit cleanup so the IDE keeps full-file line attribution
     * (git notes only carry diff hunks).
     */
    fun restoreCommitSnapshotsToBranchDir(
        repoRoot: File,
        branch: String?,
        commitShaFull: String,
        userLayoutRoot: File = blamelyUserLayoutRoot()
    ): Boolean {
        val tip = commitShaFull.trim()
        if (tip.isEmpty()) return false
        val data = resolveBlamelyDataDir(repoRoot, userLayoutRoot) ?: return false
        val srcDir = closedCommitSnapshotsDir(repoRoot, branch, tip, userLayoutRoot) ?: return false
        if (!srcDir.isDirectory) return false
        val bk = BlamelyRepoPaths.safeBranchName(branch)
        val destDir = File(File(data, "snapshots"), bk)
        destDir.mkdirs()
        var any = false
        srcDir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".blame.json") && !it.name.startsWith(".")
        }?.forEach { src ->
            try {
                src.copyTo(File(destDir, src.name), overwrite = true)
                any = true
            } catch (e: Exception) {
                BlamelyLogger.warn("Blamely: restore snapshot ${src.name}: ${e.message}")
            }
        }
        return any
    }

    /** ~/.blamely/repos/.../logs/commits/<sha>/ — per-commit report.yml + snapshots/ */
    fun commitLogDir(
        repoRoot: File,
        commitSha: String,
        userLayoutRoot: File = blamelyUserLayoutRoot()
    ): File? {
        val data = resolveBlamelyDataDir(repoRoot, userLayoutRoot) ?: return null
        val tip = commitSha.trim()
        if (tip.isEmpty()) return null
        return File(File(File(data, "logs"), "commits"), tip)
    }

    /** ~/.blamely/repos/.../logs/commits/<sha>/snapshots/ */
    @Suppress("UNUSED_PARAMETER")
    fun closedCommitSnapshotsDir(
        repoRoot: File,
        branch: String?,
        commitSha: String,
        userLayoutRoot: File = blamelyUserLayoutRoot()
    ): File? {
        val base = commitLogDir(repoRoot, commitSha, userLayoutRoot) ?: return null
        return File(base, "snapshots")
    }

    /** Legacy: ~/.blamely/repos/.../branches/<branch>/closed/<sha>/snapshots/ */
    fun legacyClosedCommitSnapshotsDir(
        repoRoot: File,
        branch: String?,
        commitSha: String,
        userLayoutRoot: File = blamelyUserLayoutRoot()
    ): File? {
        val data = resolveBlamelyDataDir(repoRoot, userLayoutRoot) ?: return null
        val bk = BlamelyRepoPaths.safeBranchName(branch)
        val tip = commitSha.trim()
        if (tip.isEmpty()) return null
        return File(File(File(File(data, "branches"), bk), "closed"), tip).resolve("snapshots")
    }
}
