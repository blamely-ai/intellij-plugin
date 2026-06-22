// IntelliJ editor live-tracker (docs/attribution-v2-design.md §9) — the platform
// counterpart to the VS Code WorkingLogTracker. CompletionDetector classifies each
// document change and calls onEdit with the full pre/post text + author; we keep a
// per-file FileTracker IN MEMORY and flush the working log to .git/blamely.
//
// All engine + I/O work runs on a single background thread (never the EDT), and the
// flush is debounced. Gated by the blamely.attributionV2 setting (default off):
// writes working-log files only; gutter/note unchanged until the Phase 3 flip.
package ai.blamely.authorship

import ai.blamely.git.GitUtils
import ai.blamely.settings.BlamelySettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class WorkingLogTracker(@Suppress("unused") private val project: Project) : Disposable {
    private val exec: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "blamely-worklog").apply { isDaemon = true } }
    private val trackers = ConcurrentHashMap<String, FileTracker>()
    private val flushTasks = ConcurrentHashMap<String, ScheduledFuture<*>>()

    /** Called by CompletionDetector for each classified change (AI + human), off
     *  the EDT via the executor. prevText is the file content BEFORE this change —
     *  the baseline when the file is first seen this session. */
    fun onEdit(absPath: String, prevText: String, newText: String, author: Author) {
        if (newText == prevText || !BlamelySettings.getInstance().attributionV2) return
        exec.submit {
            try {
                val ft = trackers.getOrPut(absPath) { FileTracker(prevText, null) }
                ft.applyEdit(newText, author)
                // An AI edit that REMOVED lines: the working log only describes surviving
                // content, so committed deletions would default to Human. Record the
                // deleted baseline lines (via the CLI, reusing the engine) so they
                // attribute to the tool. Gated to AI edits that shrink the file.
                if (author.type == AuthorType.AI && lineCount(newText) < lineCount(prevText)) {
                    recordDeletion(absPath, newText, author)
                }
                flushTasks.remove(absPath)?.cancel(false)
                flushTasks[absPath] = exec.schedule({ flush(absPath) }, FLUSH_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                // best-effort: a working-log failure must never disrupt the IDE
            }
        }
    }

    /** Flush every tracked file immediately (e.g. on IDE focus loss, before a commit
     *  reads the working log). Runs on the worklog executor for map safety. */
    fun flushAll() {
        exec.submit {
            for (path in trackers.keys.toList()) {
                flushTasks.remove(path)?.cancel(false)
                flush(path)
            }
        }
    }

    /** A commit moved HEAD: the just-committed edits are now history. Drop the
     *  in-memory trackers so the next edit re-baselines against the committed content
     *  instead of accumulating against a stale baseline. */
    fun onHeadChanged() {
        exec.submit {
            flushTasks.values.forEach { it.cancel(false) }
            flushTasks.clear()
            trackers.clear()
        }
    }

    private fun lineCount(s: String): Int = if (s.isEmpty()) 0 else s.count { it == '\n' } + 1

    /** Record AI-deleted baseline lines via `blamely record-deletion` (current content
     *  piped on stdin, since the buffer may be unsaved). Fire-and-forget; output
     *  discarded so it never blocks the worklog thread. */
    private fun recordDeletion(absPath: String, content: String, author: Author) {
        try {
            val bin = blamelyBinaryPath()
            if (!java.io.File(bin).exists()) return
            val args = mutableListOf(bin, "record-deletion", absPath, "--gen-type", author.genType.ifEmpty { "completion" })
            if (author.tool.isNotEmpty()) { args.add("--tool"); args.add(author.tool) }
            if (author.model.isNotEmpty()) { args.add("--model"); args.add(author.model) }
            val pb = ProcessBuilder(args)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            pb.environment()["BLAMELY_ATTRIBUTION_V2"] = "1"
            val p = pb.start()
            p.outputStream.use { it.write(content.toByteArray()); it.flush() }
        } catch (_: Exception) {
        }
    }

    private fun flush(absPath: String) {
        try {
            val ft = trackers[absPath] ?: return
            if (!ft.isDirty()) return
            val ctx = resolveCtx(absPath) ?: return
            val wl = ft.current() ?: return
            WorkingLogStore.save(ctx.repoRoot, ctx.branch, ctx.baseSha, ctx.rel, wl, ft.content())
            ft.markFlushed()
        } catch (_: Exception) {
        }
    }

    private data class Ctx(val repoRoot: String, val branch: String, val baseSha: String, val rel: String)

    private fun resolveCtx(absPath: String): Ctx? {
        val repoRoot = GitUtils.getRepoRoot(absPath) ?: return null
        val rel = GitUtils.toRepoRelativePath(repoRoot, absPath) ?: return null
        val branch = git(repoRoot, "rev-parse", "--abbrev-ref", "HEAD")?.takeIf { it.isNotEmpty() } ?: "DETACHED"
        val head = git(repoRoot, "rev-parse", "HEAD")?.takeIf { it.isNotEmpty() } ?: "INITIAL"
        return Ctx(repoRoot, branch, head, rel)
    }

    private fun git(cwd: String, vararg args: String): String? = try {
        val p = ProcessBuilder(listOf("git", "-C", cwd) + args).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0) out else null
    } catch (_: Exception) {
        null
    }

    override fun dispose() {
        exec.shutdownNow()
    }

    companion object {
        // Short, so a Tab-accept immediately followed by a commit is persisted before
        // the commit reads the working log.
        private const val FLUSH_DEBOUNCE_MS = 400L
    }
}
