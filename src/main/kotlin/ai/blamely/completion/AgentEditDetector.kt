package ai.blamely.completion

import ai.blamely.cli.CliDataService
import ai.blamely.cli.CliRepoId
import ai.blamely.git.GitUtils
import ai.blamely.utils.BlamelyLogger
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// AgentEditDetector records AI edits made by GitHub Copilot AGENT MODE (and any
// chat apply that creates or rewrites a file programmatically).
//
// Why this exists separately from CompletionDetector
// --------------------------------------------------
// Inline completions and "diff block" chat applies fire an editor AnAction, so
// CompletionDetector can key off the action signal + DocumentEvent. Copilot
// agent mode is different: with auto-approval it writes files straight through
// the VFS layer (creating new files, rewriting existing ones) WITHOUT firing any
// editor action and often without an open editor document. The action listener
// never sees it, so those edits were never attributed to AI (the reported bug:
// "Copilot chat panel generates a file → not detected").
//
// Detection strategy (gate on Copilot activity, per design decision)
// ------------------------------------------------------------------
//   1. Tail the IDE log (idea.log) for `#copilot - [fetchChat]` markers — these
//      are emitted by Copilot for JetBrains on every chat/agent LLM round-trip,
//      so they tell us WHEN Copilot is actively producing edits. We keep the
//      wall-clock of the most recent one in [lastCopilotChatMs].
//   2. Listen to VFS create/content-change events. When a file inside the
//      project repo changes within [COPILOT_WINDOW_MS] of chat activity, the
//      change was almost certainly written by the agent → attribute the changed
//      lines to AI (gen_type=chat) and POST to the daemon, exactly like
//      CompletionDetector does for completions.
//
// Copilot's logs never name the file it writes, so the editor (this plugin) is
// the only component that can know which file/lines changed — hence detection
// must live here, not in the CLI watchers.
@Service(Service.Level.PROJECT)
class AgentEditDetector(private val project: Project) : Disposable {

    private val daemon = DaemonClient()

    /** Wall-clock ms of the most recent `#copilot - [fetchChat]` log line. */
    @Volatile
    private var lastCopilotChatMs: Long = 0

    @Volatile
    private var stopped = false

    private val logExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "blamely-copilot-logtail").also { it.isDaemon = true }
    }

    // Last content signature sent per repo-relative path, so the burst of VFS
    // events a single agent write produces collapses into one daemon record.
    private val lastSentSig = ConcurrentHashMap<String, String>()

    private fun debugEnabled(): Boolean = try {
        ai.blamely.settings.BlamelySettings.getInstance().debugDetection
    } catch (_: Throwable) {
        false
    }

    fun register() {
        if (project.isDisposed) return
        startCopilotLogTailer()

        // VFS_CHANGES is published on the APPLICATION message bus — subscribing
        // via project.messageBus would never receive it.
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    handleVfsEvents(events)
                }
            }
        )
        BlamelyLogger.info("AgentEditDetector: registered for project ${project.name}")
    }

    // ── Copilot activity gate ──────────────────────────────────────────────

    private fun startCopilotLogTailer() {
        val ideaLog = File(PathManager.getLogPath(), "idea.log")
        logExecutor.submit {
            var raf: RandomAccessFile? = null
            var pos = 0L
            try {
                while (!stopped && !project.isDisposed) {
                    try {
                        if (raf == null) {
                            if (!ideaLog.isFile) {
                                Thread.sleep(1000)
                                continue
                            }
                            raf = RandomAccessFile(ideaLog, "r")
                            pos = raf.length() // start at EOF — ignore historical lines
                        }
                        val len = ideaLog.length()
                        if (len < pos) {
                            // Log rotated/truncated — reopen from the new EOF.
                            raf.close()
                            raf = RandomAccessFile(ideaLog, "r")
                            pos = raf.length()
                        }
                        raf.seek(pos)
                        var line = raf.readLine()
                        while (line != null) {
                            if (line.contains("#copilot") && line.contains("[fetchChat]")) {
                                lastCopilotChatMs = System.currentTimeMillis()
                                if (debugEnabled()) BlamelyLogger.info("agent: copilot chat activity")
                            }
                            line = raf.readLine()
                        }
                        pos = raf.filePointer
                    } catch (_: Exception) {
                        try { raf?.close() } catch (_: Exception) {}
                        raf = null
                    }
                    Thread.sleep(800)
                }
            } catch (_: InterruptedException) {
                // shutdownNow during dispose — exit quietly
            } finally {
                try { raf?.close() } catch (_: Exception) {}
            }
        }
    }

    // ── VFS edit attribution ───────────────────────────────────────────────

    private fun handleVfsEvents(events: List<VFileEvent>) {
        if (project.isDisposed) return
        // Gate: only consider writes that happened while Copilot was active.
        if (System.currentTimeMillis() - lastCopilotChatMs > COPILOT_WINDOW_MS) return

        val projectRoot = GitUtils.getRepoRoot(project) ?: return

        // Collect candidate (absPath, isCreate) on the calling (EDT/write) thread
        // — cheap filtering only — then offload git + disk + hashing + POST.
        val candidates = ArrayList<Pair<String, Boolean>>()
        for (ev in events) {
            val isCreate = ev is VFileCreateEvent
            if (!isCreate && ev !is VFileContentChangeEvent) continue
            val vFile = ev.file ?: continue
            if (vFile.isDirectory || !vFile.isInLocalFileSystem) continue
            val absPath = vFile.path
            if (isExcludedPath(absPath)) continue
            candidates.add(absPath to isCreate)
        }
        if (candidates.isEmpty()) return

        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            for ((absPath, isCreate) in candidates) {
                try {
                    recordAgentEdit(projectRoot, absPath, isCreate)
                } catch (_: Exception) {
                    // best-effort; never let one file abort the batch
                }
            }
        }
    }

    private fun recordAgentEdit(projectRoot: String, absPath: String, isCreate: Boolean) {
        // Only files whose git root matches this project (avoid attributing edits
        // in other repos that happen to be open elsewhere).
        if (GitUtils.getRepoRoot(absPath) != projectRoot) return

        // Pause during cherry-pick/merge/revert/rebase: a VFS write from replaying
        // history is not fresh authorship. content_sha re-attributes it afterward.
        if (GitUtils.inProgressGitOp(projectRoot)) return
        val relPath = GitUtils.toRepoRelativePath(projectRoot, absPath) ?: return

        val file = File(absPath)
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return
        val lines = try { file.readLines() } catch (_: Exception) { return }
        if (lines.isEmpty()) return

        // Which lines did the agent actually change?
        //   • tracked file → the lines that differ from HEAD (git diff)
        //   • new/untracked file → every (non-blank) line
        val changed: List<Int> = changedLinesVsHead(projectRoot, relPath).ifEmpty {
            if (isUntracked(projectRoot, relPath)) (1..lines.size).toList() else emptyList()
        }
        if (changed.isEmpty()) return

        val ranges = ArrayList<EditRange>(changed.size)
        for (ln in changed) {
            val text = lines.getOrNull(ln - 1) ?: continue
            if (text.isBlank()) continue
            ranges.add(EditRange(ln, ln, sha256Hex(text.removeSuffix("\r"))))
        }
        if (ranges.isEmpty()) return

        // Collapse the burst of VFS events from a single write into one record.
        val sig = ranges.joinToString("|") { "${it.start}:${it.contentSha}" }
        if (lastSentSig.put(relPath, sig) == sig) return

        val repoId = CliRepoId.get(projectRoot) ?: projectRoot
        val tool = resolveTool()
        val rawMeta = """{"source":"intellij_plugin_agent","gen_type_signal":"copilot_agent_vfs","create":$isCreate}"""
        val payload = EditPayload(
            tool = tool,
            confidence = "high",
            genType = "chat",
            repoPath = repoId,
            filePath = relPath,
            suggestedLines = ranges.size.toLong(),
            lines = ranges,
            rawMeta = rawMeta,
            branch = GitUtils.getBranchName(projectRoot),
        )
        if (debugEnabled()) {
            BlamelyLogger.info("agent record: tool=$tool gen_type=chat $relPath lines=${ranges.size} create=$isCreate")
        }
        if (daemon.send(payload)) {
            // Surface the new attribution promptly instead of waiting for the
            // next 2s CliDataService poll.
            project.getService(CliDataService::class.java)?.refresh()
        }
    }

    // git diff --unified=0 HEAD -- <file> → 1-based new-side changed line numbers.
    private fun changedLinesVsHead(repoRoot: String, relPath: String): List<Int> {
        val out = GitUtils.run(repoRoot, "diff", "--unified=0", "HEAD", "--", relPath) ?: return emptyList()
        val result = ArrayList<Int>()
        for (line in out.lines()) {
            if (!line.startsWith("@@ ")) continue
            val m = Regex("\\+(\\d+)(?:,(\\d+))?").find(line) ?: continue
            val start = m.groupValues[1].toIntOrNull() ?: continue
            val count = m.groupValues[2].let { if (it.isNotEmpty()) it.toIntOrNull() ?: 1 else 1 }
            for (i in 0 until count) if (start + i > 0) result.add(start + i)
        }
        return result
    }

    private fun isUntracked(repoRoot: String, relPath: String): Boolean {
        val out = GitUtils.run(repoRoot, "ls-files", "--others", "--exclude-standard", "--", relPath)
        return out != null && out.trim().isNotEmpty()
    }

    private fun isExcludedPath(absPath: String): Boolean {
        val p = absPath.replace('\\', '/')
        return EXCLUDED_DIRS.any { p.contains("/$it/") }
    }

    override fun dispose() {
        stopped = true
        logExecutor.shutdownNow()
    }

    companion object {
        // How long after a Copilot chat/agent LLM round-trip a VFS write is still
        // considered AI-authored. Agent turns stream for many seconds and write
        // files slightly afterwards, so the window is generous.
        private const val COPILOT_WINDOW_MS = 20_000L

        // Skip pathologically large files — hashing every line would be wasteful
        // and agent edits to such files are rare.
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024

        private val EXCLUDED_DIRS = setOf(
            ".git", ".idea", "build", "out", "target", "dist", "node_modules", ".gradle",
        )
    }
}
