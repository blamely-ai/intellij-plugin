package ai.blamely.git

import ai.blamely.persistence.BlamelyUserRepoPaths
import ai.blamely.utils.BlamelyLogger
import ai.blamely.utils.Platform
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Installs Blamely git hooks aligned with VS Code 1.1.0:
 *
 * - Copies **`hookRunner.js`** from plugin resources to `~/.blamely/repos/<id>/hookRunner.js`
 *   and `.git/blamely/hookRunner.js` (fallback).
 * - **pre-commit** invokes `node` on those paths so commits show hook totals and can augment the message.
 * - **pre-push** keeps a small shell runner that pushes `refs/notes/blamely` (IntelliJ-specific helper).
 *
 * Existing user hooks are preserved; Blamely appends/replaces only its marked block.
 */
object HookInstaller {

    private const val MARKER = "Blamely hookRunner"

    private val RESOURCE_HOOK_RUNNER = "/blamely/hookRunner.js"

    private val PRE_PUSH_RUNNER = """
        #!/bin/sh
        # Blamely hookRunner (pre-push)
        #
        # Pushes Blamely git notes (refs/notes/blamely) to the remote alongside the
        # user's regular push. Failure is non-fatal so it never blocks the push.
        remote="${'$'}1"
        if [ -n "${'$'}remote" ]; then
            git push "${'$'}remote" refs/notes/blamely 2>/dev/null || true
        fi
        exit 0
    """.trimIndent() + "\n"

    /** Result of an install attempt. Useful for action notifications. */
    data class Result(
        val ok: Boolean,
        val message: String
    )

    private fun inferRepoRoot(gitDir: File): File =
        if (gitDir.name == ".git") gitDir.parentFile ?: gitDir else gitDir

    fun installAll(project: Project): Result {
        val gitDirStr = GitUtils.getGitDir(project)
            ?: return Result(false, "Not a git repository (could not resolve .git directory)")
        val repoRootStr = GitUtils.getRepoRoot(project) ?: project.basePath
            ?: return Result(false, "Could not resolve repository root")
        return installAll(gitDir = File(gitDirStr), repoRoot = File(repoRootStr))
    }

    fun installAll(
        gitDir: File,
        repoRoot: File = inferRepoRoot(gitDir),
        userLayoutRoot: File = BlamelyUserRepoPaths.blamelyUserLayoutRoot()
    ): Result {
        val dataDir = BlamelyUserRepoPaths.resolveBlamelyDataDir(repoRoot, userLayoutRoot)
            ?: return Result(false, "Could not resolve Blamely repo data dir (~/.blamely/repos/…)")

        try {
            dataDir.mkdirs()
        } catch (e: Exception) {
            return Result(false, "Could not create Blamely user data dir: ${e.message}")
        }

        val primaryRunner = File(dataDir, "hookRunner.js")
        try {
            copyHookRunnerResource(primaryRunner)
        } catch (e: Exception) {
            return Result(false, "Could not install hookRunner.js: ${e.message}")
        }

        val prePushRunner = File(dataDir, "hookRunner-pre-push.sh")
        try {
            prePushRunner.writeText(PRE_PUSH_RUNNER)
            prePushRunner.setExecutable(true)
        } catch (e: Exception) {
            return Result(false, "Could not write pre-push hook runner: ${e.message}")
        }

        val hooksDir = File(gitDir, "hooks")
        if (!hooksDir.exists() && !hooksDir.mkdirs()) {
            return Result(false, "Could not create .git/hooks")
        }

        val preCommitMsg = installPreCommitHook(File(hooksDir, "pre-commit"), primaryRunner, primaryRunner)
        val prePushMsg = installHook(File(hooksDir, "pre-push"), prePushRunner, passArgs = true)
        return Result(true, "$preCommitMsg $prePushMsg")
    }

    private fun copyHookRunnerResource(dest: File) {
        dest.parentFile?.mkdirs()
        val stream = HookInstaller::class.java.getResourceAsStream(RESOURCE_HOOK_RUNNER)
            ?: throw IllegalStateException("Missing bundled resource $RESOURCE_HOOK_RUNNER")
        stream.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun winCmdQuoteArg(p: String): String =
        '"' + p.replace("\"", "\"\"") + '"'

    /**
     * Restore a previous backup (if present) and remove the Blamely block from the
     * current hook. Used by the "Restore/Remove Git Hook" action.
     */
    fun uninstallAll(project: Project): Result {
        val gitDir = GitUtils.getGitDir(project)
            ?: return Result(false, "Not a git repository (could not resolve .git directory)")
        val gd = File(gitDir)
        val hooksDir = File(gd, "hooks")
        val preCommitMsg = restoreOrRemove(File(hooksDir, "pre-commit"))
        restoreOrRemoveBat(File(hooksDir, "pre-commit.bat"))
        val prePushMsg = restoreOrRemove(File(hooksDir, "pre-push"))
        return Result(true, "$preCommitMsg $prePushMsg")
    }

    private fun restoreOrRemoveBat(batFile: File) {
        if (!batFile.exists()) return
        try {
            val text = batFile.readText()
            if (text.contains(MARKER)) {
                val cleaned = stripBatBlock(text)
                if (cleaned.isBlank()) batFile.delete()
                else batFile.writeText(cleaned)
            }
        } catch (_: Exception) {
        }
    }

    private fun shEscapeDoubleQuoted(p: String): String =
        p.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`")

    private fun preCommitBlamelyBlock(primaryRunner: File, fallbackRunner: File): String {
        val pq = shEscapeDoubleQuoted(primaryRunner.normalize().absolutePath)
        val fq = shEscapeDoubleQuoted(fallbackRunner.normalize().absolutePath)
        val inner = """
                if [ -f "$pq" ]; then
                  exec node "$pq" "${'$'}@"
                fi
                if [ -f "$fq" ]; then
                  exec node "$fq" "${'$'}@"
                fi
        """.trimIndent()
        return """
            # >>> $MARKER (start) >>>
            # Runs bundled hookRunner.js from ~/.blamely/repos/…/hookRunner.js (no .git/blamely writes).
            $inner
            echo "[blamely] hookRunner.js missing — skipping pre-commit helper (run Blamely: Install Git Hook)"
            exit 0
            # <<< $MARKER (end) <<<
        """.trimIndent() + "\n"
    }

    /**
     * Windows Git often runs `pre-commit.bat`; append the same Blamely block so `cmd` sees it.
     */
    private fun preCommitBlamelyBlockBat(primaryRunner: File, fallbackRunner: File): String {
        val pq = winCmdQuoteArg(primaryRunner.normalize().absolutePath)
        val fq = winCmdQuoteArg(fallbackRunner.normalize().absolutePath)
        return """
            rem >>> $MARKER (start) >>>
            IF EXIST $pq (node $pq %* & exit /b 0)
            IF EXIST $fq (node $fq %* & exit /b 0)
            echo [blamely] hookRunner.js missing — skipping
            rem <<< $MARKER (end) <<<

        """.trimIndent().replace("\n", "\r\n")
    }

    private fun installPreCommitHook(hookFile: File, primaryRunner: File, fallbackRunner: File): String {
        val block = preCommitBlamelyBlock(primaryRunner, fallbackRunner)
        val msg = installHookGeneric(hookFile, block, isNewShebang = "#!/bin/sh\n")
        mergePreCommitBatSibling(hookFile, primaryRunner, fallbackRunner)
        return msg
    }

    private fun installHook(hookFile: File, runnerScript: File, passArgs: Boolean): String {
        val absRunner = runnerScript.absolutePath
        val argSuffix = if (passArgs) " \"\$@\"" else ""
        val block = """
            # >>> $MARKER (start) >>>
            # Calls the Blamely hook runner stored at .git/blamely/${runnerScript.name} so it
            # survives plugin upgrades / reinstalls (the runner path is stable per repo).
            if [ -x "$absRunner" ]; then
                "$absRunner"$argSuffix || true
            fi
            # <<< $MARKER (end) <<<
        """.trimIndent() + "\n"
        return installHookGeneric(hookFile, block, isNewShebang = "#!/bin/sh\n")
    }

    private fun installHookGeneric(hookFile: File, block: String, isNewShebang: String): String {
        return when {
            !hookFile.exists() -> {
                try {
                    hookFile.writeText(isNewShebang + "\n$block\nexit 0\n")
                    hookFile.setExecutable(true)
                    "Created ${hookFile.name}."
                } catch (e: Exception) {
                    "Could not create ${hookFile.name}: ${e.message}."
                }
            }
            else -> {
                try {
                    val current = hookFile.readText()
                    if (current.contains(MARKER)) {
                        val rewritten = replaceBlock(current, block)
                        if (rewritten != current) {
                            hookFile.writeText(rewritten)
                            hookFile.setExecutable(true)
                            "${hookFile.name}: refreshed Blamely block."
                        } else {
                            "${hookFile.name}: already up to date."
                        }
                    } else {
                        backupHookOnce(hookFile)
                        hookFile.writeText(current.trimEnd() + "\n\n" + block + "\n")
                        hookFile.setExecutable(true)
                        "${hookFile.name}: appended Blamely block (your existing hook is preserved)."
                    }
                } catch (e: Exception) {
                    BlamelyLogger.warn("Blamely: hook update failed for ${hookFile.absolutePath}: ${e.message}")
                    "Could not update ${hookFile.name}: ${e.message}."
                }
            }
        }
    }

    private fun replaceBatBlock(content: String, newBlock: String): String {
        val startMarker = "rem >>> $MARKER (start) >>>"
        val endMarker = "rem <<< $MARKER (end) <<<"
        val startIdx = content.indexOf(startMarker)
        val endIdx = content.indexOf(endMarker)
        if (startIdx < 0 || endIdx < 0 || endIdx < startIdx) return content
        val before = content.substring(0, startIdx).trimEnd()
        val afterStart = endIdx + endMarker.length
        val after = if (afterStart < content.length) content.substring(afterStart).trimStart() else ""
        val sb = StringBuilder()
        if (before.isNotEmpty()) sb.append(before).append("\r\n\r\n")
        sb.append(newBlock.trimEnd()).append("\r\n")
        if (after.isNotEmpty()) sb.append("\r\n").append(after)
        return sb.toString()
    }

    private fun mergePreCommitBatSibling(shellHook: File, primary: File, fallback: File) {
        if (shellHook.name != "pre-commit" || !Platform.isWindows()) return
        val bat = File(shellHook.parentFile, "pre-commit.bat")
        val properBatBlock = preCommitBlamelyBlockBat(primary, fallback)
        try {
            if (!bat.exists()) {
                bat.writeText("@echo off\r\n\r\n$properBatBlock\r\nexit /b 0\r\n")
            } else {
                val cur = bat.readText()
                if (cur.contains(MARKER)) {
                    bat.writeText(replaceBatBlock(cur, properBatBlock))
                } else {
                    bat.writeText(cur.trimEnd() + "\r\n\r\n" + properBatBlock)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun replaceBlock(content: String, newBlock: String): String {
        val startMarker = "# >>> $MARKER (start) >>>"
        val endMarker = "# <<< $MARKER (end) <<<"
        val startIdx = content.indexOf(startMarker)
        val endIdx = content.indexOf(endMarker)
        if (startIdx < 0 || endIdx < 0 || endIdx < startIdx) return content
        val before = content.substring(0, startIdx).trimEnd()
        val afterStart = endIdx + endMarker.length
        val after = if (afterStart < content.length) content.substring(afterStart).trimStart() else ""
        val sb = StringBuilder()
        if (before.isNotEmpty()) sb.append(before).append("\n\n")
        sb.append(newBlock.trimEnd()).append("\n")
        if (after.isNotEmpty()) sb.append("\n").append(after)
        return sb.toString()
    }

    private fun stripBatBlock(content: String): String {
        val startMarker = "rem >>> $MARKER (start) >>>"
        val endMarker = "rem <<< $MARKER (end) <<<"
        val startIdx = content.indexOf(startMarker)
        val endIdx = content.indexOf(endMarker)
        if (startIdx < 0 || endIdx < 0 || endIdx < startIdx) return content
        val before = content.substring(0, startIdx).trimEnd()
        val afterStart = endIdx + endMarker.length
        val after = if (afterStart < content.length) content.substring(afterStart).trim() else ""
        val sb = StringBuilder()
        if (before.isNotEmpty()) sb.append(before).append("\r\n")
        if (after.isNotEmpty()) sb.append(after).append("\r\n")
        return sb.toString()
    }

    private fun backupHookOnce(hookFile: File) {
        val backup = File(hookFile.parentFile, "${hookFile.name}.blamely.backup")
        if (backup.exists()) return
        try {
            hookFile.copyTo(backup, overwrite = false)
        } catch (e: Exception) {
            BlamelyLogger.warn("Blamely: hook backup failed for ${hookFile.absolutePath}: ${e.message}")
        }
    }

    private fun restoreOrRemove(hookFile: File): String {
        val backup = File(hookFile.parentFile, "${hookFile.name}.blamely.backup")
        return when {
            backup.exists() -> {
                try {
                    backup.copyTo(hookFile, overwrite = true)
                    hookFile.setExecutable(true)
                    backup.delete()
                    "${hookFile.name}: restored from backup."
                } catch (e: Exception) {
                    "${hookFile.name}: restore failed (${e.message})."
                }
            }
            hookFile.exists() -> {
                try {
                    val cleaned = stripBlock(hookFile.readText())
                    if (cleaned.trim().isEmpty() || cleaned.trim() == "#!/bin/sh") {
                        hookFile.delete()
                        "${hookFile.name}: removed (only contained Blamely block)."
                    } else {
                        hookFile.writeText(cleaned)
                        "${hookFile.name}: removed Blamely block."
                    }
                } catch (e: Exception) {
                    "${hookFile.name}: cleanup failed (${e.message})."
                }
            }
            else -> "${hookFile.name}: nothing to remove."
        }
    }

    private fun stripBlock(content: String): String {
        val startMarker = "# >>> $MARKER (start) >>>"
        val endMarker = "# <<< $MARKER (end) <<<"
        val startIdx = content.indexOf(startMarker)
        val endIdx = content.indexOf(endMarker)
        if (startIdx < 0 || endIdx < 0 || endIdx < startIdx) return content
        val before = content.substring(0, startIdx).trimEnd()
        val afterStart = endIdx + endMarker.length
        val after = if (afterStart < content.length) content.substring(afterStart).trim() else ""
        val sb = StringBuilder()
        if (before.isNotEmpty()) sb.append(before).append("\n")
        if (after.isNotEmpty()) sb.append(after).append("\n")
        return sb.toString()
    }
}
