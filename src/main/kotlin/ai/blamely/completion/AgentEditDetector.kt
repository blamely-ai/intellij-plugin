package ai.blamely.completion

import ai.blamely.cli.CliDataService
import ai.blamely.cli.CliRepoId
import ai.blamely.core.BlameMapService
import ai.blamely.git.GitUtils
import ai.blamely.utils.BlamelyLogger
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
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

    // Pre-write content baseline, keyed by absolute path: the document text as it
    // looked JUST BEFORE the current change. Captured on every documentChange (so
    // it includes lines the human typed between AI applies), then consumed when an
    // agent write is recorded — we diff the new file content against THIS instead
    // of against HEAD, so an agent rewrite doesn't sweep human-typed lines into AI.
    // This is the IntelliJ analogue of the VS Code plugin's putSnapshot baseline.
    private val preWriteBaseline = ConcurrentHashMap<String, String>()

    private fun debugEnabled(): Boolean = try {
        ai.blamely.settings.BlamelySettings.getInstance().debugDetection
    } catch (_: Throwable) {
        false
    }

    fun register() {
        if (project.isDisposed) return
        startCopilotLogTailer()

        // Capture the pre-change document text for every edit. beforeDocumentChange
        // fires while document.text is still the OLD content — so right before an
        // agent rewrites an open file, this records the human's latest content as
        // the diff baseline. Parented to `this` so it's removed on dispose.
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun beforeDocumentChange(event: DocumentEvent) {
                    val path = FileDocumentManager.getInstance().getFile(event.document)?.path ?: return
                    if (isExcludedPath(path)) return
                    preWriteBaseline[path] = event.document.text
                }
            },
            this,
        )

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
        //   • PREFERRED: diff against the pre-write baseline (the file as it looked
        //     immediately before this write, captured by the document listener and
        //     INCLUDING any lines the human typed since the last apply). This is the
        //     fix for "AI → human edit → AI rewrite re-claims the human's lines":
        //     lines already in the baseline are excluded, so only the agent's new
        //     lines are attributed. Mirrors the VS Code plugin's snapshot baseline.
        //   • FALLBACK (no baseline — e.g. agent created a file with no open editor):
        //     tracked file → lines that differ from HEAD; new/untracked → every line.
        val baseline = preWriteBaseline.remove(absPath)
        val baselineChanged: List<Int>? = baseline?.let {
            changedNewLines(it.split('\n').map { l -> l.removeSuffix("\r") }, lines)
        }
        // Stash the baseline in the daemon too (parity with the VS Code plugin) so
        // any CLI-side narrowing that compares against a "before" snapshot agrees.
        if (baseline != null) {
            daemon.putSnapshot(CliRepoId.get(projectRoot) ?: projectRoot, relPath, baseline)
        }
        val changed: List<Int> = baselineChanged ?: changedLinesVsHead(projectRoot, relPath).ifEmpty {
            if (hasNoHeadVersion(projectRoot, relPath)) (1..lines.size).toList() else emptyList()
        }
        if (debugEnabled()) {
            val via = if (baselineChanged != null) "baseline(${baseline?.length} chars)" else "HEAD-diff (no baseline)"
            BlamelyLogger.info("agent narrow: $relPath via=$via changedLines=${changed.size} -> ${changed.take(20)}")
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
        // Bridge the daemon-write race so the gutter/status bar (which paint from
        // BlameMapService's "current changes" pending lines, same as CompletionDetector)
        // show this agent edit as AI immediately, instead of only after the row lands
        // in SQLite and a later refresh picks it up.
        val blameService = project.getService(BlameMapService::class.java)
        for (range in ranges) {
            blameService?.markPendingAiLines(relPath, range.start..range.end, tool, null, "chat")
        }
        if (daemon.send(payload)) {
            // Surface the new attribution promptly instead of waiting for the
            // next 2s CliDataService poll.
            project.getService(CliDataService::class.java)?.refresh()
        }
    }

    // changedNewLines returns the 1-based line numbers in [new] that are insertions
    // or changes relative to [old] — i.e. NOT part of the longest common subsequence
    // of the two line lists. Lines that already existed in [old] (including ones the
    // human typed) are excluded, so an agent rewrite is narrowed to its genuinely
    // new lines. Returns null when the inputs are too large to diff cheaply, so the
    // caller falls back to the HEAD diff.
    private fun changedNewLines(old: List<String>, new: List<String>): List<Int>? {
        val n = old.size
        val m = new.size
        if (n == 0) return (1..m).toList()
        if (n.toLong() * m > 6_000_000L) return null // guard the O(n*m) DP
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            val oi = old[i]
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (oi == new[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val changed = ArrayList<Int>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                old[i] == new[j] -> { i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> { changed.add(j + 1); j++ }
            }
        }
        while (j < m) { changed.add(j + 1); j++ }
        return changed
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

    // True when there is no committed version of this file to diff against —
    // either it's untracked, or the repo has no commits yet (HEAD doesn't
    // resolve), or it was just staged without ever existing at HEAD. In all
    // these cases the file's whole content is new, so every line is "changed".
    private fun hasNoHeadVersion(repoRoot: String, relPath: String): Boolean {
        if (isUntracked(repoRoot, relPath)) return true
        return GitUtils.run(repoRoot, "cat-file", "-e", "HEAD:$relPath") == null
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
