package ai.blamely.listeners

import ai.blamely.core.BlameMapService
import ai.blamely.core.BranchSessionLifecycleService
import ai.blamely.core.TraceStoreService
import ai.blamely.git.GitUtils
import ai.blamely.utils.BlamelyLogger
import ai.blamely.persistence.BlameSerializer
import ai.blamely.persistence.BlamelyUserRepoPaths
import ai.blamely.report.ReportYaml
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ToolWindowManager
import ai.blamely.ui.BlamelyStatusBarWidget
import com.intellij.util.Alarm
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Listens for new Git commits and: sets commit_sha on blame, generates report.yml,
 * writes blamely-detector.ai for hookRunner.js, attaches git note, clears on-disk snapshots
 * under ~/.blamely/repos/… (VS Code layout).
 * Works with IntelliJ Git integration (Git4Idea) and polling fallback.
 * Uses Alarm instead of Thread.sleep to avoid holding pooled threads and reduce UI freeze risk.
 */
class CommitListener(private val project: Project) {

    private val lastKnownSha = AtomicReference<String?>(null)
    private val log = Logger.getInstance(CommitListener::class.java)
    private val pollCount = AtomicLong(0)
    private var lastNoRepoLog = 0L
    private val basePath: String? = project.basePath
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)

    /**
     * Sets up commit detection. Safe to call from any thread; all blocking Git
     * operations run on a pooled thread to avoid EDT / read-action deadlocks.
     */
    fun start() {
        // Subscribe to Git4Idea on whatever thread we're on (message bus is thread-safe)
        project.messageBus.connect(project).subscribe(
            git4idea.repo.GitRepository.GIT_REPO_CHANGE,
            git4idea.repo.GitRepositoryChangeListener { _ ->
                for (delayMs in listOf(300, 800, 1500)) {
                    alarm.addRequest(
                        {
                            if (project.isDisposed) return@addRequest
                            checkForNewCommit()
                        },
                        delayMs
                    )
                }
            }
        )

        // Resolve initial HEAD SHA and start polling via Alarm (no blocking loop)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val repoRoot = GitUtils.getRepoRoot(project)
                val initialSha = getHeadSha()
                lastKnownSha.set(initialSha)
                val msgStart = "CommitListener start — repoRoot=${repoRoot ?: "null"}, HEAD=${initialSha?.take(8) ?: "null"}"
                log.warn("Blamely: [COMMIT] $msgStart")
                BlamelyLogger.warn("Blamely: [COMMIT] $msgStart")
                BlamelyLogger.warn("Blamely: [COMMIT] listening for commits (GIT_REPO_CHANGE + 2s polling)")
                log.warn("Blamely: [COMMIT] listening for commits (GIT_REPO_CHANGE + 2s polling)")
            } catch (e: Exception) {
                log.warn("Blamely: [COMMIT] initial resolution failed: ${e.message}")
            }
            schedulePoll()
        }
    }

    private fun schedulePoll() {
        if (project.isDisposed) return
        alarm.addRequest(
            {
                if (project.isDisposed) return@addRequest
                checkForNewCommit()
                try {
                    project.getService(BranchSessionLifecycleService::class.java)?.pollStashAndLink()
                } catch (_: Exception) {
                }
                schedulePoll()
            },
            2000
        )
    }

    private fun getHeadSha(): String? {
        val root = GitUtils.getRepoRoot(project) ?: return null
        return GitUtils.run(root, "rev-parse", "HEAD")
    }

    private fun checkForNewCommit() {
        val currentSha = getHeadSha()
        if (currentSha == null) {
            val now = System.currentTimeMillis()
            if (now - lastNoRepoLog > 30_000) {
                lastNoRepoLog = now
            }
            return
        }
        val count = pollCount.incrementAndGet()
        if (currentSha == lastKnownSha.get()) {
            if (count % 30 == 1L && count > 1) {
                // polling
            }
            return
        }
        onCommitDetected(currentSha)
    }

    fun onCommitDetected(commitSha: String) {
        if (commitSha == lastKnownSha.get()) return
        val repoRoot = GitUtils.getRepoRoot(project) ?: project.basePath
        if (repoRoot != null && !GitUtils.isLocalCommit(repoRoot, commitSha)) {
            lastKnownSha.set(commitSha)
            log.info("Blamely: [COMMIT] SHA changed to ${commitSha.take(8)} but not a local commit (pull/fetch) — skipping blame processing")
            return
        }
        lastKnownSha.set(commitSha)
        alarm.addRequest(
            { if (!project.isDisposed) handlePostCommit(commitSha) },
            1000
        )
    }

    fun handlePostCommit(commitSha: String) {
        val repoRoot = GitUtils.getRepoRoot(project)
        if (repoRoot == null) return

        val blameService = project.getService(BlameMapService::class.java) ?: return
        blameService.ensureBranchLoaded()
        val traceService = project.getService(TraceStoreService::class.java) ?: return
        val blameMap = blameService.blameMap
        val traceStore = traceService.traceStore

        var changedRepoRelative = GitUtils.getFilesChangedInCommit(repoRoot, commitSha).toSet()
        if (changedRepoRelative.isEmpty()) {
            changedRepoRelative = GitUtils.getFilesChangedInCommit(repoRoot, commitSha).toSet()
        }
        val changedProjectRelative = GitUtils.repoRelativeToProjectRelative(repoRoot, project.basePath, changedRepoRelative)
        log.info("Blamely: [COMMIT] changed files (${changedProjectRelative.size}): ${changedProjectRelative.take(10).joinToString(", ") { it.substringAfterLast('/').ifEmpty { it } } }${if (changedProjectRelative.size > 10) "..." else ""}")

        // Parse the full diff for each file: added lines, deleted lines.
        // Use repo-relative path (file path) as the key for blames.
        val fileDiffs = mutableMapOf<String, GitUtils.FileDiffStats>()
        val projectRelToRepoPath = mutableMapOf<String, String>()
        for (repoRel in changedRepoRelative) {
            val stats = GitUtils.getDiffStats(repoRoot, commitSha, repoRel)
            val projectRelSet = GitUtils.repoRelativeToProjectRelative(repoRoot, project.basePath, listOf(repoRel))
            val projectRel = projectRelSet.singleOrNull() ?: continue
            if (stats.addedCount > 0 || stats.deletedCount > 0) {
                val projNorm = projectRel.replace('\\', '/')
                val repoPath = repoRel.replace('\\', '/')
                fileDiffs[projNorm] = stats
                projectRelToRepoPath[projNorm] = repoPath
            }
        }
        // Build blame snapshot from the commit diff.
        // Added lines get AI/HUMAN attribution from the tracker.
        // Deleted lines are recorded with authorType=HUMAN (deletion is always a human action).
        // If any AI entry has no model, try to get current model from UI once (commit-time fallback).
        val ts = java.time.Instant.now().toString()
        var fallbackModel: String? = null
        val needsFallbackModel = fileDiffs.values.any { stats ->
            stats.addedLines.isNotEmpty()
        } && fileDiffs.keys.any { projectRel ->
            blameMap.getBlame(projectRel).any { it.authorType == ai.blamely.core.LineBlame.AuthorType.AI && (it.model.isNullOrBlank() || it.model == "unknown") }
        }
        if (needsFallbackModel) {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
                val ctx = ai.blamely.utils.AiContextExtractor.extractFromProject(project)
                fallbackModel = ai.blamely.utils.AiContextExtractor.sanitizeModelForReport(ctx.model)
            }
        }
        val entireBlame = mutableMapOf<String, List<ai.blamely.core.LineBlame>>()
        for ((projectRel, stats) in fileDiffs) {
            val trackerBlame = blameMap.getBlame(projectRel)
            val trackerByLine = trackerBlame.associateBy { it.lineNumber }
            val entries = mutableListOf<ai.blamely.core.LineBlame>()

            for (line in stats.addedLines.sorted()) {
                val tracked = trackerByLine[line]
                val authorType = tracked?.authorType ?: ai.blamely.core.LineBlame.AuthorType.HUMAN
                val provider = if (authorType == ai.blamely.core.LineBlame.AuthorType.AI) (tracked?.provider ?: "unknown") else null
                var rawModel = if (authorType == ai.blamely.core.LineBlame.AuthorType.AI) tracked?.model else null
                if (authorType == ai.blamely.core.LineBlame.AuthorType.AI && (rawModel.isNullOrBlank() || rawModel == "unknown") && fallbackModel != null) {
                    rawModel = fallbackModel
                }
                val model = if (authorType == ai.blamely.core.LineBlame.AuthorType.AI) (ai.blamely.utils.AiContextExtractor.sanitizeModelForReport(rawModel) ?: "unknown") else null
                val prompt = if (authorType == ai.blamely.core.LineBlame.AuthorType.AI) tracked?.prompt else null
                entries.add(ai.blamely.core.LineBlame(
                    lineNumber = line,
                    authorType = authorType,
                    provider = provider,
                    model = model,
                    prompt = prompt,
                    timestamp = ts,
                    commitSha = commitSha,
                    aiChars = if (authorType == ai.blamely.core.LineBlame.AuthorType.AI) 1 else 0,
                    humanChars = if (authorType == ai.blamely.core.LineBlame.AuthorType.HUMAN) 1 else 0,
                    changeType = ai.blamely.core.LineBlame.ChangeType.ADD,
                    newLineNumber = line,
                    oldLineNumber = null
                ))
            }

            for (oldLine in stats.deletedLines.sorted()) {
                val deletedByAi = blameMap.wasLineDeletedByAi(projectRel, oldLine)
                val authorType = if (deletedByAi) ai.blamely.core.LineBlame.AuthorType.AI else ai.blamely.core.LineBlame.AuthorType.HUMAN
                entries.add(ai.blamely.core.LineBlame(
                    lineNumber = oldLine,
                    authorType = authorType,
                    provider = if (deletedByAi) "github-copilot" else null,
                    model = if (deletedByAi) "unknown" else null,
                    timestamp = ts,
                    commitSha = commitSha,
                    aiChars = if (deletedByAi) 1 else 0,
                    humanChars = if (deletedByAi) 0 else 1,
                    changeType = ai.blamely.core.LineBlame.ChangeType.DELETE,
                    newLineNumber = null,
                    oldLineNumber = oldLine
                ))
            }

            if (entries.isNotEmpty()) {
                val filePath = projectRelToRepoPath[projectRel] ?: projectRel
                entireBlame[filePath] = entries.sortedBy { it.newLineNumber ?: it.oldLineNumber ?: 0 }
                val aiAdded = stats.addedLines.count { l -> trackerByLine[l]?.authorType == ai.blamely.core.LineBlame.AuthorType.AI }
                val humanAdded = stats.addedCount - aiAdded
                val aiDeleted = stats.deletedLines.count { blameMap.wasLineDeletedByAi(projectRel, it) }
                val humanDeleted = stats.deletedCount - aiDeleted
                log.info("Blamely: [BLAME] file=${projectRel.substringAfterLast('/')} +${stats.addedCount}(AI=$aiAdded,Human=$humanAdded) -${stats.deletedCount}(AI=$aiDeleted,Human=$humanDeleted) commit=${commitSha.take(8)}")
            }
        }

        val totalAdded = fileDiffs.values.sumOf { it.addedCount }
        val totalDeleted = fileDiffs.values.sumOf { it.deletedCount }
        log.info("Blamely: [COMMIT] git note for ${commitSha.take(8)}: ${fileDiffs.size} file(s), +$totalAdded/-$totalDeleted lines")

        val reportMetrics = ai.blamely.report.ReportMetrics(
            firstStartCodingTimeMs = blameMap.firstStartCodingTimeMs,
            timeWaitingForAiMs = blameMap.totalTimeWaitingForAiMs
        )
        val yamlReport = ReportYaml.generateFromBlameSnapshot(project, entireBlame, traceStore, commitSha, "IntelliJ", reportMetrics)
        val snapshotYaml = ReportYaml.blameSnapshotToYaml(entireBlame)
        val noteContent = "${yamlReport}blames:\n$snapshotYaml"

        val hookTotals = ReportYaml.computeHookTotalsFromBlameSnapshot(entireBlame)
        ReportYaml.writeBlamelyDetectorAi(project, yamlReport, hookTotals)

        if (entireBlame.isEmpty()) {
            log.info("Blamely: [COMMIT] no blame for changed files (${changedProjectRelative.size} files); attaching note with empty blames.")
        } else {
            log.info("Blamely: [BLAME] snapshot for commit ${commitSha.take(8)}: ${fileDiffs.size} file(s), +$totalAdded/-$totalDeleted")
        }
        log.info("Blamely: [COMMIT] adding git note from repo root: $repoRoot")

        // Per-branch report.yml under `.git/blamely/<sanitized-branch>/report.yml`. We
        // write the report (without the blame snapshot block) so the file mirrors what
        // the VS Code extension keeps per branch and survives a `git checkout`.
        try {
            val gitDirPath = GitUtils.getGitDirForCwd(repoRoot)
            val branch = GitUtils.getBranchForCwd(repoRoot)
            if (gitDirPath != null) {
                val target = ai.blamely.persistence.BlamelyRepoPaths.reportFile(java.io.File(gitDirPath), branch)
                target.parentFile?.mkdirs()
                target.writeText(yamlReport)
            }
            BlamelyUserRepoPaths.reportYamlFile(java.io.File(repoRoot), branch)?.let { userReport ->
                userReport.parentFile?.mkdirs()
                userReport.writeText(yamlReport, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            log.warn("Blamely: per-branch report.yml write failed: ${e.message}")
        }

        var commitNoteAttached = GitUtils.addGitNote(repoRoot, commitSha, noteContent)
        if (commitNoteAttached) {
            if (!GitUtils.pushGitNotes(repoRoot)) {
                // push notes failed (no upstream?)
            }
        } else {
            val tmp = java.io.File.createTempFile("blamely-note-", ".txt")
            try {
                tmp.writeText(noteContent)
                val (code, _, _) = GitUtils.runWithStderr(repoRoot, "notes", "--ref=blamely", "add", "-F", tmp.absolutePath, "-f", commitSha)
                commitNoteAttached = code == 0
            } finally {
                tmp.delete()
            }
        }

        project.getService(BranchSessionLifecycleService::class.java)
            ?.closeSessionAfterCommit(commitSha, commitNoteAttached)

        // Clear snapshots on disk (safe from any thread)
        BlameSerializer.clearCurrentBranchSnapshots(project)
        // Schedule UI update on EDT without blocking this thread (avoids deadlock when EDT is stuck)
        ApplicationManager.getApplication().invokeLater outer@ {
            if (project.isDisposed) return@outer
            blameService.commitSuppressUntil = System.currentTimeMillis() + 5000
            blameMap.clear()
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(BlamelyStatusBarWidget.WIDGET_ID)
            project.messageBus.syncPublisher(ai.blamely.core.BlameUpdateListener.TOPIC).blameUpdated()
            val tw = ToolWindowManager.getInstance(project).getToolWindow("Blamely")
            if (tw != null) {
                tw.activate {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        val historyContent = tw.contentManager.contents.find { it.displayName == "History" }
                            ?: tw.contentManager.contents.getOrNull(1)
                        if (historyContent != null) tw.contentManager.setSelectedContent(historyContent)
                    }
                }
            }
        }
    }
}
