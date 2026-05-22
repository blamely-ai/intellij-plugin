package ai.blamely

import ai.blamely.core.BlameMapService
import ai.blamely.core.TraceStoreService
import ai.blamely.git.GitUtils
import ai.blamely.listeners.CommitListener
import ai.blamely.listeners.DocumentChangeTracker
import ai.blamely.listeners.PushNoteListener
import ai.blamely.persistence.BlameSerializer
import ai.blamely.persistence.BlamelyUserRepoPaths
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import java.beans.PropertyChangeListener
import java.io.File

/**
 * Runs when project is opened: load persisted blame, register document listener and commit listener.
 * Uses StartupActivity.DumbAware (not ProjectActivity) for maximum compatibility with 2023.2.
 */
class BlamelyStartupActivity : StartupActivity, DumbAware {

    private val log = Logger.getInstance(BlamelyStartupActivity::class.java)

    override fun runActivity(project: Project) {
        System.err.println("[Blamely] runActivity() entered for project=${project.basePath}")

        try {
            runActivityImpl(project)
        } catch (t: Throwable) {
            System.err.println("[Blamely] FATAL: startup activity crashed: ${t.javaClass.name}: ${t.message}")
            t.printStackTrace(System.err)
        }
    }

    private fun runActivityImpl(project: Project) {
        val basePath = project.basePath
        System.err.println("[Blamely] runActivityImpl basePath=$basePath")

        val msgStart = "Blamely: startup activity running for project=$basePath"
        log.warn(msgStart)
        System.err.println("[Blamely] checking services...")

        val blameService = project.getService(BlameMapService::class.java)
        if (blameService == null) {
            System.err.println("[Blamely] BlameMapService not available, skipping")
            return
        }
        val traceService = project.getService(TraceStoreService::class.java)
        if (traceService == null) {
            System.err.println("[Blamely] TraceStoreService not available, skipping")
            return
        }
        val blameMap = blameService.blameMap

        fun refreshStatusBarAndDecorations() {
            if (project.isDisposed) return
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)?.updateWidget(ai.blamely.ui.BlamelyStatusBarWidget.WIDGET_ID)
                project.getService(ai.blamely.ui.BlameDecorations::class.java).refresh()
                com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
                project.messageBus.syncPublisher(ai.blamely.core.BlameUpdateListener.TOPIC).blameUpdated()
            }
        }

        val onBlameUpdated: () -> Unit = { refreshStatusBarAndDecorations() }

        val reloadBlameFromDiskAndCli: () -> Unit = fun() {
            if (project.isDisposed) {
                return
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                if (project.isDisposed) return@executeOnPooledThread
                try {
                    val repoRoot = GitUtils.getRepoRoot(project) ?: project.basePath
                    val hasLocalChanges =
                        repoRoot != null && GitUtils.hasUncommittedChanges(repoRoot)
                    val restored = if (hasLocalChanges) BlameSerializer.loadAll(project) else emptyMap()
                    if (!hasLocalChanges) {
                        BlameSerializer.clearCurrentBranchSnapshots(project)
                    }
                    if (project.isDisposed) return@executeOnPooledThread
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        restored.forEach { (path, entries) ->
                            if (entries.isEmpty()) return@forEach
                            if (basePath != null && !isPathUnderProject(basePath, path)) return@forEach
                            blameMap.setFileBlame(path, entries)
                        }
                        ai.blamely.cli.CliTraceToBlame.populateFromCliSessions(project, blameMap)
                        refreshStatusBarAndDecorations()
                    }
                } catch (_: Exception) {
                }
            }
        }

        val changeTracker = DocumentChangeTracker(project, onBlameUpdated)
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(changeTracker, project)

        val statusBarAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)
        fun scheduleStatusBarRefresh() {
            if (project.isDisposed) return
            statusBarAlarm.addRequest(
                {
                    if (!project.isDisposed) {
                        refreshStatusBarAndDecorations()
                        scheduleStatusBarRefresh()
                    }
                },
                2000,
                false
            )
        }
        scheduleStatusBarRefresh()

        basePath?.let { bp ->
            val dataDir = BlamelyUserRepoPaths.resolveBlamelyDataDir(File(bp), BlamelyUserRepoPaths.blamelyUserLayoutRoot())
            val dataPrefix = dataDir?.absolutePath?.let { it + File.separator } ?: ""
            val legacyPrefix = BlamelyUserRepoPaths.cliTraceParentDir(File(bp)).absolutePath + File.separator
            project.messageBus.connect(project).subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: MutableList<out VFileEvent>) {
                        if (events.none { ev ->
                                val p = ev.file?.path ?: return@none false
                                val underData =
                                    dataPrefix.isNotEmpty() && p.startsWith(dataPrefix) &&
                                        p.contains("${File.separator}snapshots${File.separator}") && p.endsWith(".blame.json")
                                underData || p.startsWith(legacyPrefix)
                            }) {
                            return
                        }
                        if (project.isDisposed) return
                        reloadBlameFromDiskAndCli()
                    }
                }
            )
        }

        // Restore blame history on a background thread to avoid blocking the EDT during startup.
        // File I/O + git CLI calls can deadlock if run under the IDE's write-lock contention.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val repoRoot = GitUtils.getRepoRoot(project) ?: project.basePath
                val hasLocalChanges =
                    repoRoot != null && GitUtils.hasUncommittedChanges(repoRoot)
                if (!hasLocalChanges) {
                    BlameSerializer.clearCurrentBranchSnapshots(project)
                }
                val restored = if (hasLocalChanges) BlameSerializer.loadAll(project) else emptyMap()
                val branch = ai.blamely.git.GitUtils.getBranch(project)
                if (project.isDisposed) return@executeOnPooledThread
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    restored.forEach { (path, entries) ->
                        if (entries.isEmpty()) return@forEach
                        if (basePath != null && !isPathUnderProject(basePath, path)) return@forEach
                        blameMap.setFileBlame(path, entries)
                    }
                    blameService.setLastLoadedBranch(branch)
                    if (branch != null) {
                        project.getService(ai.blamely.core.BranchSessionLifecycleService::class.java)?.onBranchChanged(branch)
                    }
                    val session = BlameSerializer.loadSession(project)
                    blameMap.restoreSessionMetrics(session.first_start_coding_time_ms, session.total_time_waiting_for_ai_ms)
                    if (restored.isNotEmpty()) {
                        log.info("Blamely: blame map restored — ${restored.size} file(s), ${restored.values.sumOf { it.size }} line(s)")
                    }
                    ai.blamely.cli.CliTraceToBlame.populateFromCliSessions(project, blameMap)
                    refreshStatusBarAndDecorations()
                }
            } catch (e: Exception) {
                log.warn("Blamely: blame restore failed: ${e.message}")
            }
        }

        val lookupManager = LookupManager.getInstance(project)
        @Suppress("DEPRECATION")
        lookupManager.addPropertyChangeListener(PropertyChangeListener { ev ->
            if (ev.propertyName == LookupManager.PROP_ACTIVE_LOOKUP && ev.newValue != null) {
                val lookup = ev.newValue as? com.intellij.codeInsight.lookup.LookupEx
                if (lookup != null && isAiProviderLookup(lookup)) {
                    val providerName = detectAiProvider(lookup)
                    val model = ai.blamely.utils.AiContextExtractor.extractFromProject(project).model
                    changeTracker.markNextChangeAsAi(
                        durationMs = 2000,
                        provider = providerName,
                        model = model,
                        interactionType = "completion"
                    )
                }
            }
        }, project)

        // Intercept ALL actions to detect AI tool activity (Copilot completion, Chat inline,
        // Chat panel "Apply", Codeium, Tabnine, etc.). Fires BEFORE the action executes so
        // we can mark the subsequent document change as AI.
        var lastActionLog = 0L
        project.messageBus.connect(project).subscribe(
            com.intellij.openapi.actionSystem.ex.AnActionListener.TOPIC,
            object : com.intellij.openapi.actionSystem.ex.AnActionListener {
                override fun beforeActionPerformed(action: com.intellij.openapi.actionSystem.AnAction, event: com.intellij.openapi.actionSystem.AnActionEvent) {
                    val actionId = com.intellij.openapi.actionSystem.ActionManager.getInstance().getId(action) ?: ""
                    val className = action.javaClass.name

                    if (isUndoRedoAction(actionId)) {
                        changeTracker.notifyUndoRedo()
                    } else if (isRollbackAction(actionId, className)) {
                        changeTracker.notifyRollback()
                    } else if (isChatSendOrSubmitAction(actionId, className)) {
                        changeTracker.recordChatRequestSent()
                    } else if (isAiRelatedAction(actionId, className)) {
                        val ctx = ai.blamely.utils.AiContextExtractor.extract(project, action, event)
                        val duration = when (ctx.interactionType) {
                            "chat_panel", "chat_inline" -> 15_000L
                            "completion" -> 2_000L
                            else -> 4_000L
                        }
                        changeTracker.markNextChangeAsAi(
                            durationMs = duration,
                            prompt = ctx.prompt,
                            model = ctx.model,
                            provider = ctx.provider,
                            interactionType = ctx.interactionType
                        )
                    } else if (isPasteAction(actionId, className)) {
                        changeTracker.notifyPaste()
                    }

                    // Always log AI-related actions; throttle (1/sec) for others
                    val now = System.currentTimeMillis()
                    val isAiAction = AI_CLASS_PACKAGES.any { className.lowercase().contains(it) }
                        || AI_PROVIDER_KEYWORDS.any { actionId.lowercase().contains(it) }
                    if (isAiAction) {
                        // AI action detected
                    } else if (now - lastActionLog > 1000 && actionId.isNotBlank()
                        && !actionId.startsWith("Editor")
                    ) {
                        lastActionLog = now
                    }
                }
            }
        )

        // CommandListener: catches WriteCommandAction from AI chat panels (Copilot, Codeium, etc.)
        // that bypass the action system entirely.
        var lastCmdLog = 0L
        project.messageBus.connect(project).subscribe(
            com.intellij.openapi.command.CommandListener.TOPIC,
            object : com.intellij.openapi.command.CommandListener {
                override fun commandStarted(event: com.intellij.openapi.command.CommandEvent) {
                    val cmdName = event.commandName ?: ""
                    val cmdClass = event.command.javaClass.name
                    val cmdNameLower = cmdName.lowercase()
                    val cmdClassLower = cmdClass.lowercase()

                    val isApplyCommand = isChatPanelApplyCommand(cmdNameLower, cmdClassLower)
                    val isSendCommand = isChatSendCommand(cmdNameLower, cmdClassLower)
                    val isAiCommand = AI_CLASS_PACKAGES.any { cmdClassLower.contains(it) }
                        || AI_PROVIDER_KEYWORDS.any { cmdNameLower.contains(it) }
                        || AI_CLASS_PACKAGES.any { cmdNameLower.contains(it) }
                        || isApplyCommand

                    // Record when user sends a message so time_waiting_for_ai_ms = (apply time - send time)
                    if (isSendCommand) {
                        changeTracker.recordChatRequestSent()
                    }
                    if (isRollbackCommand(cmdNameLower, cmdClassLower)) {
                        changeTracker.notifyRollback()
                    }
                    if (isAiCommand) {
                        val ctx = ai.blamely.utils.AiContextExtractor.extractFromProject(project)
                        changeTracker.markNextChangeAsAi(
                            durationMs = 15_000L,
                            prompt = ctx.prompt,
                            model = ctx.model,
                            provider = ctx.provider,
                            interactionType = "chat_panel"
                        )
                    }

                    val now = System.currentTimeMillis()
                    if (cmdName.isNotBlank() && now - lastCmdLog > 1000) {
                        lastCmdLog = now
                    }
                }

                override fun commandFinished(event: com.intellij.openapi.command.CommandEvent) {
                    val cmdNameLower = (event.commandName ?: "").lowercase()
                    val cmdClassLower = event.command.javaClass.name.lowercase()
                    if (!isRollbackCommand(cmdNameLower, cmdClassLower)) return
                    refreshStatusBarAndDecorations()
                }
            }
        )

        val commitListener = CommitListener(project)
        commitListener.start()
        project.getService(ai.blamely.core.BranchSessionLifecycleService::class.java)?.initializeOnStartup()

        val msgReady = "Blamely: plugin ready — CommitListener and blame tracking active"
        log.warn(msgReady)
        System.err.println("[Blamely] $msgReady")

        // When user pushes to remote, also push refs/notes/blamely (if IDE supports GitPushListener)
        PushNoteListener.register(project)

        // When a file is deleted/moved/renamed, update blame and recalculate status bar
        ApplicationManager.getApplication().messageBus.connect(project).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out com.intellij.openapi.vfs.newvfs.events.VFileEvent>) {
                    val projectBase = project.basePath ?: return
                    var needsRefresh = false
                    for (event in events) {
                        when (event) {
                            is VFileDeleteEvent -> {
                                val relativePath = toProjectRelative(event.file.path, projectBase) ?: continue
                                val svc = project.getService(BlameMapService::class.java) ?: continue
                                if (svc.blameMap.getBlame(relativePath).isEmpty()) continue
                                svc.blameMap.removeFile(relativePath)
                                needsRefresh = true
                            }
                            is VFileMoveEvent -> {
                                val oldParent = event.oldParent.path
                                val newPath = event.file.path
                                val oldPath = "$oldParent/${event.file.name}"
                                val oldRel = toProjectRelative(oldPath, projectBase) ?: continue
                                val newRel = toProjectRelative(newPath, projectBase) ?: continue
                                val svc = project.getService(BlameMapService::class.java) ?: continue
                                if (svc.blameMap.getBlame(oldRel).isEmpty()) continue
                                svc.blameMap.moveFile(oldRel, newRel)
                                needsRefresh = true
                            }
                            is VFilePropertyChangeEvent -> {
                                if (event.propertyName != VirtualFile.PROP_NAME) continue
                                val parent = event.file.parent?.path ?: continue
                                val oldName = event.oldValue as? String ?: continue
                                val newName = event.newValue as? String ?: continue
                                val oldRel = toProjectRelative("$parent/$oldName", projectBase) ?: continue
                                val newRel = toProjectRelative("$parent/$newName", projectBase) ?: continue
                                val svc = project.getService(BlameMapService::class.java) ?: continue
                                if (svc.blameMap.getBlame(oldRel).isEmpty()) continue
                                svc.blameMap.moveFile(oldRel, newRel)
                                needsRefresh = true
                            }
                            is VFileCopyEvent -> {
                                val newFile = event.newParent.findChild(event.newChildName) ?: continue
                                if (newFile.isDirectory) continue
                                val newRel = toProjectRelative(newFile.path, projectBase) ?: continue
                                val svc = project.getService(BlameMapService::class.java) ?: continue
                                try {
                                    val content = String(newFile.contentsToByteArray(), newFile.charset)
                                    val lines = content.split("\n")
                                    if (lines.isEmpty()) continue
                                    val charsPerLine = lines.map { if (it.isBlank()) 1 else it.length }
                                    val normalizedPath = ai.blamely.utils.Platform.normalizePath(newRel)
                                    svc.blameMap.recordFirstStartCodingTimeIfNeeded()
                                    svc.blameMap.setAttribute(
                                        filePath = normalizedPath,
                                        lineStart = 1,
                                        lineEnd = lines.size,
                                        authorType = ai.blamely.core.LineBlame.AuthorType.HUMAN,
                                        charsInserted = charsPerLine.sum(),
                                        charsPerLineOverride = charsPerLine,
                                        codingType = ai.blamely.core.LineBlame.CodingType.BULK_INSERT
                                    )
                                    needsRefresh = true
                                } catch (_: Exception) { }
                            }
                        }
                    }
                    if (needsRefresh) {
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed) return@invokeLater
                            com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
                                ?.updateWidget(ai.blamely.ui.BlamelyStatusBarWidget.WIDGET_ID)
                            project.messageBus.syncPublisher(ai.blamely.core.BlameUpdateListener.TOPIC).blameUpdated()
                        }
                    }
                }

                private fun toProjectRelative(path: String, projectBase: String): String? {
                    if (!path.startsWith(projectBase)) return null
                    val rel = path.substring(projectBase.length).trimStart('/', '\\')
                    return rel.ifEmpty { null }
                }
            }
        )

        // Ensure pre-push hook is installed so notes are pushed automatically on every push (IDE or CLI)
        ApplicationManager.getApplication().executeOnPooledThread {
            ensurePrePushHookInstalled(project)
        }

        // Persist all blame when project is closing so history is kept after reopen
        project.messageBus.connect(project).subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
            override fun projectClosing(proj: Project) {
                if (proj != project) return
                val svc = project.getService(BlameMapService::class.java) ?: return
                val tracked = svc.blameMap.getTrackedFiles()
                tracked.forEach { path ->
                    val entries = svc.blameMap.getBlame(path)
                    if (entries.isNotEmpty()) BlameSerializer.save(project, path, entries)
                }
                BlameSerializer.saveSession(project, svc.blameMap)
                project.getService(ai.blamely.core.BranchSessionLifecycleService::class.java)?.touchSession()
            }
        })

        project.messageBus.connect().subscribe(com.intellij.AppTopics.FILE_DOCUMENT_SYNC, object : com.intellij.openapi.fileEditor.FileDocumentManagerListener {
            override fun beforeDocumentSaving(document: com.intellij.openapi.editor.Document) {
                blameService.ensureBranchLoaded()
                val file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document) ?: return
                val bp = project.basePath ?: return
                var path = file.path
                if (path.startsWith(bp)) path = path.substring(bp.length).trimStart('/', '\\')
                val entries = blameMap.getBlame(path)
                if (entries.isNotEmpty()) {
                    BlameSerializer.save(project, path, entries)
                }
                BlameSerializer.saveSession(project, blameMap)
                project.getService(ai.blamely.core.BranchSessionLifecycleService::class.java)?.touchSession()
                if (ai.blamely.settings.BlamelySettings.getInstance().reportOnSave) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            val dir = ai.blamely.git.GitUtils.getBlamelyDir(project) ?: return@executeOnPooledThread
                            dir.mkdirs()
                            val yaml = ai.blamely.report.ReportYaml.generateAndPersistDetector(project, blameMap, traceService.traceStore, ideName = "IntelliJ")
                            java.io.File(dir, "report.yml").writeText(yaml)
                            // Also write a per-branch copy so each branch keeps its latest report.yml.
                            val branch = ai.blamely.git.GitUtils.getBranch(project)
                            val gitDir = ai.blamely.git.GitUtils.getGitDir(project)
                            if (gitDir != null) {
                                val branchReport = ai.blamely.persistence.BlamelyRepoPaths.reportFile(java.io.File(gitDir), branch)
                                branchReport.parentFile?.mkdirs()
                                branchReport.writeText(yaml)
                            }
                        } catch (_: Exception) { }
                    }
                }
            }
        })
    }

    /**
     * Ensure both pre-commit and pre-push hooks are installed via [ai.blamely.git.HookInstaller].
     * The runner script under `.git/blamely/hookRunner-*.sh` keeps the install stable across
     * plugin upgrades (the hook just shells out to an absolute repo-local path).
     */
    private fun ensurePrePushHookInstalled(project: Project) {
        try {
            val settings = ai.blamely.settings.BlamelySettings.getInstance()
            if (!settings.state.autoInstallHook) return
            val result = ai.blamely.git.HookInstaller.installAll(project)
            log.info("Blamely: hook auto-install — ${result.message}")
        } catch (e: Exception) {
            log.warn("Blamely: hook auto-install failed: ${e.message}")
        }
    }

    companion object {
        /** True if path is under project (excludes Dummy.txt, scratch, absolute outside paths). */
        private fun isPathUnderProject(basePath: String, filePath: String): Boolean {
            val normalized = filePath.replace('\\', '/')
            if (normalized.contains("..") || normalized.startsWith("/")) return false
            val file = java.io.File(basePath, normalized)
            return try {
                file.exists() && file.canonicalPath.startsWith(java.io.File(basePath).canonicalPath)
            } catch (_: Exception) {
                false
            }
        }

        private val AI_PROVIDER_KEYWORDS = listOf(
            "copilot", "github.copilot",
            "codeium",
            "tabnine",
            "cursor", "cursor-ai",
            "supermaven",
            "codegpt", "code-gpt",
            "ai-assistant", "aiassistant",
            "jetbrains.ai", "jb-ai",
            "amazon.q", "codewhisperer",
            "gemini"
        )

        /**
         * Returns true only when the lookup popup contains items from a known AI code completion
         * provider (Copilot, Cursor, Codeium, Tabnine, etc.). Standard IntelliJ completions
         * (keywords, variables, methods, imports) are NOT treated as AI.
         */
        private fun isAiProviderLookup(lookup: com.intellij.codeInsight.lookup.LookupEx): Boolean {
            try {
                for (item in lookup.items) {
                    val className = item.javaClass.name.lowercase()
                    if (AI_PROVIDER_KEYWORDS.any { className.contains(it) }) return true

                    val psiElement = item.psiElement
                    if (psiElement != null) {
                        val originClass = psiElement.javaClass.name.lowercase()
                        if (AI_PROVIDER_KEYWORDS.any { originClass.contains(it) }) return true
                    }

                    val presentation = com.intellij.codeInsight.lookup.LookupElementPresentation()
                    item.renderElement(presentation)
                    val presentText = presentation.itemText?.lowercase() ?: ""
                    val tailText = presentation.tailText?.lowercase() ?: ""
                    val typeText = presentation.typeText?.lowercase() ?: ""
                    val combined = "$presentText $tailText $typeText"
                    if (AI_PROVIDER_KEYWORDS.any { combined.contains(it) }) return true
                }
            } catch (_: Throwable) {}
            return false
        }

        private fun detectAiProvider(lookup: com.intellij.codeInsight.lookup.LookupEx): String {
            try {
                for (item in lookup.items) {
                    val className = item.javaClass.name.lowercase()
                    for (keyword in AI_PROVIDER_KEYWORDS) {
                        if (className.contains(keyword)) return keyword
                    }
                }
            } catch (_: Throwable) {}
            return "unknown-ai"
        }

        private fun isPasteAction(actionId: String, className: String): Boolean {
            val id = actionId.lowercase()
            val cls = className.lowercase()
            return id == "editorpaste" || id == "\$paste" || id == "editorpastesimple"
                || id == "pastemultiple" || id == "editorpastefromx11"
                || id.contains("paste")
                || cls.contains("pasteaction") || cls.contains("paste")
        }

        private fun isUndoRedoAction(actionId: String): Boolean {
            return actionId == "\$Undo" || actionId == "\$Redo"
                || actionId == "EditorUndo" || actionId == "EditorRedo"
        }

        private fun isRollbackAction(actionId: String, className: String): Boolean {
            val id = actionId.lowercase()
            val cls = className.lowercase()
            if (id == "changesview.revert" || id == "vcs.rollback"
                || id == "git.revert" || id == "changesview.rollback"
                || id == "rollback" || id == "vcs.rollbackchangedlines"
                || id == "changesview.revertfiles"
            ) return true
            if (cls.contains("localhistory") || cls.contains("com.intellij.history")) return true
            if (id.contains("rollback") || id.contains("revert")) return true
            if (cls.contains("rollback") || cls.contains("revertaction")) return true
            return false
        }

        /** Rollback / restore file commands that may not go through AnActionListener the same way. */
        private fun isRollbackCommand(cmdNameLower: String, cmdClassLower: String): Boolean {
            if (cmdClassLower.contains("localhistory") || cmdClassLower.contains("com.intellij.history")) return true
            if (cmdNameLower.contains("rollback") && (cmdNameLower.contains("file") || cmdNameLower.contains("group") || cmdNameLower.contains("change"))) return true
            if (cmdNameLower.contains("revert") && cmdNameLower.contains("change")) return true
            if (cmdNameLower.contains("restore") && cmdNameLower.contains("file")) return true
            return false
        }

        /** Known AI provider package/class fragments. */
        private val AI_CLASS_PACKAGES = listOf(
            "com.github.copilot",
            "copilot",
            "codeium",
            "tabnine",
            "supermaven",
            "codegpt",
            "amazon.q", "codewhisperer", "aws.toolkit",
            "jetbrains.ai",
            "cursor"
        )

        /**
         * Returns true if the action originates from a known AI provider. This covers:
         * - Copilot ghost text completion (Tab accept)
         * - Copilot Chat Inline (Ctrl+I) apply/accept
         * - Copilot Chat Panel "Apply" / "Insert at cursor"
         * - Codeium, Tabnine, Supermaven, JetBrains AI, Amazon Q
         * - IntelliJ built-in inline completion framework
         *
         * We check BOTH the action ID and the fully qualified class name.
         */
        private fun isAiRelatedAction(actionId: String, className: String): Boolean {
            val id = actionId.lowercase()
            val cls = className.lowercase()

            // Any action whose class is from an AI provider package
            if (AI_CLASS_PACKAGES.any { cls.contains(it) }) return true

            // Action ID contains an AI provider name
            if (AI_PROVIDER_KEYWORDS.any { id.contains(it) }) return true

            // IntelliJ built-in inline completion framework actions
            if (id.contains("inlinecompletion") || id.contains("inline.completion")) return true
            if (id.contains("inline") && id.contains("suggest")) return true

            // Generic apply/insert/accept from AI-sounding actions
            if ((id.contains("apply") || id.contains("accept") || id.contains("insert"))
                && (id.contains("chat") || id.contains("ai") || id.contains("generated"))) return true

            // Chat panel: Apply to document, Insert at cursor, Apply code (class or id often generic)
            if (id.contains("apply") || id.contains("insert") || id.contains("accept")) {
                if (cls.contains("copilot") || cls.contains("codeium") || cls.contains("tabnine")
                    || cls.contains("cursor") || cls.contains("chat") || cls.contains("ai.assistant")) return true
            }
            if (cls.contains("apply") && (cls.contains("document") || cls.contains("editor") || cls.contains("code"))) {
                if (cls.contains("copilot") || cls.contains("codeium") || cls.contains("chat") || cls.contains("github")) return true
            }

            return false
        }

        /** True when user sends/submits a message in chat (so we can measure time_waiting_for_ai from send to apply). */
        private fun isChatSendOrSubmitAction(actionId: String, className: String): Boolean {
            val id = actionId.lowercase()
            val cls = className.lowercase()
            val fromChat = id.contains("chat") || cls.contains("chat") || cls.contains("copilot") || cls.contains("codeium")
                || cls.contains("tabnine") || cls.contains("cursor")
            if (!fromChat) return false
            return id.contains("send") || id.contains("submit") || id.contains("post") || id.contains("ask")
                || id.contains("message") || cls.contains("send") || cls.contains("submit") || cls.contains("post") || cls.contains("ask")
        }

        /** Commands that apply chat panel output to the editor (often generic names). */
        private fun isChatPanelApplyCommand(cmdNameLower: String, cmdClassLower: String): Boolean {
            if (cmdNameLower.contains("apply") || cmdNameLower.contains("insert") || cmdNameLower.contains("write")) {
                if (cmdClassLower.contains("copilot") || cmdClassLower.contains("codeium") || cmdClassLower.contains("tabnine")
                    || cmdClassLower.contains("cursor") || cmdClassLower.contains("chat") || cmdClassLower.contains("jetbrains.ai")) return true
            }
            return false
        }

        /** Commands that send/submit a chat message (so we can measure time_waiting_for_ai from send to apply). */
        private fun isChatSendCommand(cmdNameLower: String, cmdClassLower: String): Boolean {
            val fromChat = cmdClassLower.contains("copilot") || cmdClassLower.contains("codeium") || cmdClassLower.contains("tabnine")
                || cmdClassLower.contains("cursor") || cmdClassLower.contains("chat") || cmdNameLower.contains("chat")
            if (!fromChat) return false
            return cmdNameLower.contains("send") || cmdNameLower.contains("submit") || cmdNameLower.contains("post")
                || cmdNameLower.contains("message") || cmdNameLower.contains("ask")
                || cmdClassLower.contains("send") || cmdClassLower.contains("submit") || cmdClassLower.contains("post")
        }
    }
}
