package ai.blamely.ui

import ai.blamely.core.BlameMapService
import ai.blamely.core.BlameUpdateListener
import ai.blamely.core.BranchSessionLifecycleService
import ai.blamely.core.BranchSessionListEntry
import ai.blamely.core.LineBlame
import ai.blamely.git.GitUtils
import ai.blamely.persistence.HomeBranchSession
import ai.blamely.settings.BlamelyConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import javax.swing.*

class BlamelyToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val cf = ContentFactory.getInstance()

        val currentPanel = CurrentChangesPanel(project)
        val currentContent = cf.createContent(currentPanel, "Changes", false)
        toolWindow.contentManager.addContent(currentContent)

        val overallPanel = OverallChangesPanel(project)
        val overallContent = cf.createContent(overallPanel, "History", false)
        toolWindow.contentManager.addContent(overallContent)

        val settingsActionGroup = DefaultActionGroup().apply {
            add(OpenBlamelySettingsAction(project))
        }
        val place = ActionPlaces.TOOLBAR
        currentContent.setActions(settingsActionGroup, place, currentPanel)
        overallContent.setActions(settingsActionGroup, place, overallPanel)
    }
}

/** Opens Settings → Tools → Blamely. Shown as gear icon in tool window header. */
private class OpenBlamelySettingsAction(private val project: Project) : AnAction(
    "Blamely Settings",
    "Open Blamely settings (status bar, gutter icons)",
    AllIcons.General.Gear
) {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, BlamelyConfigurable::class.java)
    }
}

// ─── Current Changes ─────────────────────────────────────────────────────────

private class CurrentChangesPanel(private val project: Project) : JPanel(BorderLayout()) {

    companion object {
        /** Changes tab lists only the most recent branch sessions to keep the panel compact. */
        private const val MAX_BRANCH_SESSIONS_ON_CHANGES_TAB = 3
    }

    // Design colors
    private val colBgSecondary = java.awt.Color(0x2B, 0x2D, 0x30)
    private val colBgElevated = java.awt.Color(0x31, 0x34, 0x38)
    private val colBgHover = java.awt.Color(0x2E, 0x30, 0x35)
    private val colBorder = java.awt.Color(0x3D, 0x40, 0x45)
    private val colBorderSubtle = java.awt.Color(0x2A, 0x2C, 0x30)
    private val colTextPrimary = java.awt.Color(0xDF, 0xE1, 0xE5)
    private val colTextSecondary = java.awt.Color(0x9D, 0xA0, 0xA8)
    private val colTextMuted = java.awt.Color(0x6B, 0x6F, 0x76)
    private val colAi = java.awt.Color(0x4D, 0x9D, 0xE0)
    private val colAiBg = java.awt.Color(0x4D, 0x9D, 0xE0, 30)
    private val colHuman = java.awt.Color(0x56, 0xA0, 0x64)
    private val colHumanBg = java.awt.Color(0x56, 0xA0, 0x64, 30)
    private val colDelete = java.awt.Color(0xE0, 0x6C, 0x75)

    private fun hex(c: java.awt.Color) = "#%02X%02X%02X".format(c.red, c.green, c.blue)
    private fun withAlpha(c: java.awt.Color, a: Int) = java.awt.Color(c.red, c.green, c.blue, a)

    private val modelValueLabel = JLabel("\u2014").apply {
        foreground = colTextPrimary; font = font.deriveFont(java.awt.Font.BOLD, 11f)
    }
    private val summaryPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = true
        background = colBgSecondary
    }
    private val fileListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
    }
    private val statusLabel = JLabel("").apply { foreground = colTextMuted; font = font.deriveFont(10f) }

    private val basePath: String get() = project.basePath ?: ""

    init {
        background = java.awt.Color(0x1E, 0x1F, 0x22)

        // Toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = true; background = colBgSecondary
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, colBorder),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)
            )
        }
        val modelSelector = object : JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)) {
            init {
                isOpaque = false; cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                border = BorderFactory.createCompoundBorder(
                    object : javax.swing.border.AbstractBorder() {
                        override fun paintBorder(c: java.awt.Component, g: java.awt.Graphics, x: Int, y: Int, w: Int, h: Int) {
                            val g2 = g as java.awt.Graphics2D
                            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                            g2.color = colBorder; g2.drawRoundRect(x, y, w - 1, h - 1, 4, 4)
                        }
                    },
                    BorderFactory.createEmptyBorder(1, 4, 1, 4)
                )
                add(JLabel("AI model").apply { foreground = colTextMuted; font = font.deriveFont(10f) })
                add(JLabel("|").apply { foreground = colBorder })
                add(modelValueLabel)
            }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = colBgElevated; g2.fillRoundRect(0, 0, width, height, 4, 4)
            }
        }
        toolbar.add(modelSelector)
        add(toolbar, BorderLayout.NORTH)

        // Center: title + summary + scrollable sections
        val center = JPanel(BorderLayout()).apply { isOpaque = false }
        val titleStrip = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(12, 16, 8, 16)
            val titles = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
            titles.add(JLabel("Working tree").apply {
                foreground = colTextPrimary
                font = font.deriveFont(java.awt.Font.BOLD, 13f)
            })
            titles.add(Box.createVerticalStrut(3))
            titles.add(JLabel("Live attribution and branch sessions").apply {
                foreground = colTextMuted
                font = font.deriveFont(11f)
            })
            add(titles, BorderLayout.WEST)
        }
        summaryPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, colBorderSubtle),
            BorderFactory.createEmptyBorder(10, 16, 12, 16)
        )
        val centerNorth = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        centerNorth.add(titleStrip)
        centerNorth.add(summaryPanel)
        center.add(centerNorth, BorderLayout.NORTH)
        val scroll = JScrollPane(fileListPanel).apply {
            border = BorderFactory.createEmptyBorder(8, 14, 14, 14)
            verticalScrollBar.unitIncrement = 16
            viewport.isOpaque = false
            isOpaque = false
        }
        center.add(scroll, BorderLayout.CENTER)
        add(center, BorderLayout.CENTER)

        // Status bar
        val statusBar = JPanel(FlowLayout(FlowLayout.LEFT, 12, 4)).apply {
            isOpaque = true
            background = colBgSecondary
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, colBorderSubtle),
                BorderFactory.createEmptyBorder(6, 16, 8, 16)
            )
            add(statusLabel)
        }
        add(statusBar, BorderLayout.SOUTH)

        refreshView()
        project.messageBus.connect(project).subscribe(BlameUpdateListener.TOPIC, object : BlameUpdateListener {
            override fun blameUpdated() {
                ApplicationManager.getApplication().invokeLater { refreshView() }
            }
        })
    }

    private fun refreshView() {
        if (project.isDisposed) return
        val blameService = project.getService(BlameMapService::class.java) ?: return
        val blameMap = blameService.blameMap
        val base = basePath

        val repoRoot = GitUtils.getRepoRoot(project)
        val sessionsAll = if (repoRoot != null) {
            project.getService(BranchSessionLifecycleService::class.java)?.listSessionsForToolWindow() ?: emptyList()
        } else {
            emptyList()
        }
        val sessions = sessionsAll
            .sortedByDescending { e ->
                e.closedAt?.takeIf { it.isNotBlank() }
                    ?: e.updatedAt.takeIf { it.isNotBlank() }
                    ?: e.openedAt
            }
            .take(MAX_BRANCH_SESSIONS_ON_CHANGES_TAB)
        val branchName = GitUtils.getBranch(project)

        val trackedFiles = if (base.isNotEmpty()) {
            blameMap.getTrackedFiles().filter { isPathUnderProject(base, it) }
        } else {
            emptyList()
        }

        // Collect models
        val models = mutableSetOf<String>()
        for (fp in trackedFiles) {
            blameMap.getBlame(fp).filter { it.authorType == LineBlame.AuthorType.AI && !it.model.isNullOrBlank() }
                .mapNotNull { ai.blamely.utils.AiContextExtractor.sanitizeModelForReport(it.model) }
                .forEach { models.add(it) }
        }
        modelValueLabel.text = if (models.isEmpty()) "\u2014" else models.sorted().joinToString(", ")

        // Aggregate totals for summary
        var totalAiChars = 0; var totalHumanChars = 0; var totalAiLines = 0; var totalHumanLines = 0

        data class FileStats(
            val path: String,
            val displayName: String,
            val aiChars: Int,
            val humanChars: Int,
            val aiLines: Int,
            val humanLines: Int,
            val aiPct: Double,
            val gitInsertions: Int,
            val gitDeletions: Int
        )
        val fileStatsList = mutableListOf<FileStats>()

        val numstatByPath = if (repoRoot != null) GitUtils.getWorkingTreeNumstatVsHead(repoRoot) else emptyMap()
        val wtShortStat = if (repoRoot != null) GitUtils.getWorkingTreeDiffShortStat(repoRoot) else GitUtils.DiffShortStat(0, 0, 0)

        for (fp in trackedFiles) {
            val entries = blameMap.getBlame(fp).filter { it.commitSha == null }
            if (entries.isEmpty()) continue
            val aiC = entries.sumOf { it.aiChars }
            val hC = entries.sumOf { it.humanChars }
            val aiL = entries.count { it.authorType == LineBlame.AuthorType.AI }
            val hL = entries.count { it.authorType == LineBlame.AuthorType.HUMAN }
            totalAiChars += aiC; totalHumanChars += hC; totalAiLines += aiL; totalHumanLines += hL
            val total = aiC + hC
            val pct = if (total > 0) 100.0 * aiC / total else 0.0
            val name = fp.substringAfterLast('/').ifEmpty { fp }
            val norm = ai.blamely.utils.Platform.normalizePath(fp)
            val diffPair = numstatByPath[norm]
            val ins = diffPair?.first ?: 0
            val del = diffPair?.second ?: 0
            fileStatsList.add(FileStats(fp, name, aiC, hC, aiL, hL, pct, ins, del))
        }

        // Summary strip
        summaryPanel.removeAll()
        val totalChars = totalAiChars + totalHumanChars
        val aiPctTotal = if (totalChars > 0) 100.0 * totalAiChars / totalChars else 0.0
        val humanPctTotal = 100.0 - aiPctTotal

        summaryPanel.add(summaryStatDot(colAi))
        summaryPanel.add(Box.createHorizontalStrut(4))
        summaryPanel.add(summaryNum("$totalAiChars", colAi))
        summaryPanel.add(summaryMuted(" chars "))
        summaryPanel.add(summaryNum("$totalAiLines", colAi))
        summaryPanel.add(summaryMuted(" lines "))
        summaryPanel.add(Box.createHorizontalStrut(4))
        summaryPanel.add(summaryPct("${"%.0f".format(aiPctTotal)}%", colAi, colAiBg))
        summaryPanel.add(Box.createHorizontalStrut(8))
        summaryPanel.add(summarySegBar(aiPctTotal / 100.0, humanPctTotal / 100.0))
        summaryPanel.add(Box.createHorizontalStrut(8))
        summaryPanel.add(summaryStatDot(colHuman))
        summaryPanel.add(Box.createHorizontalStrut(4))
        summaryPanel.add(summaryNum("$totalHumanChars", colHuman))
        summaryPanel.add(summaryMuted(" chars "))
        summaryPanel.add(summaryNum("$totalHumanLines", colHuman))
        summaryPanel.add(summaryMuted(" lines "))
        summaryPanel.add(Box.createHorizontalStrut(4))
        summaryPanel.add(summaryPct("${"%.0f".format(humanPctTotal)}%", colHuman, colHumanBg))
        if (repoRoot != null && (wtShortStat.insertions > 0 || wtShortStat.deletions > 0)) {
            summaryPanel.add(Box.createHorizontalStrut(12))
            summaryPanel.add(JLabel("|").apply {
                foreground = colBorder
                alignmentY = java.awt.Component.CENTER_ALIGNMENT
            })
            summaryPanel.add(Box.createHorizontalStrut(8))
            summaryPanel.add(summaryMuted("Δ vs HEAD "))
            summaryPanel.add(summaryNum("+${wtShortStat.insertions}", colHuman))
            summaryPanel.add(summaryMuted(" ins "))
            summaryPanel.add(summaryNum("\u2212${wtShortStat.deletions}", colDelete))
            summaryPanel.add(summaryMuted(" del "))
            summaryPanel.add(Box.createHorizontalStrut(6))
            summaryPanel.add(summaryInsertDeleteBar(wtShortStat.insertions, wtShortStat.deletions))
        }
        summaryPanel.add(Box.createHorizontalGlue())
        summaryPanel.add(JLabel("${fileStatsList.size} file${if (fileStatsList.size != 1) "s" else ""}").apply {
            foreground = colTextMuted
            font = font.deriveFont(11f)
            alignmentY = java.awt.Component.CENTER_ALIGNMENT
        })
        for (i in 0 until summaryPanel.componentCount) {
            val c = summaryPanel.getComponent(i)
            if (c is JComponent) c.alignmentY = java.awt.Component.CENTER_ALIGNMENT
        }

        // File list: card — branch & sessions; card — working-tree files
        fileListPanel.removeAll()
        val branchSessionsInner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        val branchHeader = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
            add(JLabel("Branch").apply { foreground = colTextMuted; font = font.deriveFont(java.awt.Font.BOLD, 10f) })
            add(branchBadge(branchName ?: "—"))
        }
        branchSessionsInner.add(branchHeader)
        branchSessionsInner.add(Box.createVerticalStrut(4))
        val openCount = sessions.count { it.status == HomeBranchSession.STATUS_OPEN || it.status == HomeBranchSession.STATUS_STASHED }
        val stashCount = sessions.sumOf { it.stashLinkCount }
        val closedCount = sessions.count { it.status == HomeBranchSession.STATUS_CLOSED }
        val sessionsTruncated = sessionsAll.size > sessions.size
        val sessionsTitle = buildString {
            append("Sessions")
            if (sessionsTruncated) append(" (latest ").append(MAX_BRANCH_SESSIONS_ON_CHANGES_TAB).append(")")
            if (openCount > 0) append(" · ").append(openCount).append(" open")
            if (stashCount > 0) append(" · ").append(stashCount).append(" stash")
            if (closedCount > 0) append(" · ").append(closedCount).append(" recent")
        }
        branchSessionsInner.add(groupRow(sessionsTitle, sessions.size))
        when {
            repoRoot == null -> branchSessionsInner.add(mutedRow("No Git repository — branch sessions are not available."))
            sessions.isEmpty() -> branchSessionsInner.add(
                mutedRow("No sessions for this branch yet. They appear when you edit with Blamely tracking active.")
            )
            else -> {
                for (s in sessions) {
                    branchSessionsInner.add(sessionRow(s))
                }
                if (sessionsTruncated) {
                    branchSessionsInner.add(
                        mutedRow("+ ${sessionsAll.size - sessions.size} older session(s) — open History for committed reports.")
                    )
                }
            }
        }
        fileListPanel.add(changesRoundedCard(branchSessionsInner))
        fileListPanel.add(Box.createVerticalStrut(12))

        val changesInner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        changesInner.add(groupRow("Files", fileStatsList.size))
        changesInner.add(Box.createVerticalStrut(4))
        if (fileStatsList.isEmpty()) {
            changesInner.add(mutedRow("No uncommitted attribution yet — edit tracked files to see AI vs human breakdown."))
        } else {
            for (fs in fileStatsList) {
                changesInner.add(fileRow(fs.displayName, fs.path, fs.aiChars, fs.humanChars, fs.aiLines, fs.humanLines, fs.aiPct, fs.gitInsertions, fs.gitDeletions))
            }
        }
        fileListPanel.add(changesRoundedCard(changesInner))

        // Status
        val interactionTypes = mutableSetOf<String>()
        for (fp in trackedFiles) {
            blameMap.getBlame(fp).filter { it.commitSha == null && !it.interactionType.isNullOrBlank() }
                .forEach { interactionTypes.add(it.interactionType!!) }
        }
        val parts = mutableListOf<String>()
        parts.add("${fileStatsList.size} files tracked")
        if (interactionTypes.isNotEmpty()) parts.add(interactionTypes.sorted().joinToString(", "))
        statusLabel.text = "\u25CF Tracking \u00b7 ${parts.joinToString(" \u00b7 ")}"

        summaryPanel.revalidate(); summaryPanel.repaint()
        fileListPanel.revalidate(); fileListPanel.repaint()
    }

    // ── Summary helpers ──

    private fun summaryStatDot(color: java.awt.Color): JPanel {
        return object : JPanel() {
            init { preferredSize = java.awt.Dimension(7, 7); isOpaque = false }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color; g2.fillOval(0, 0, 7, 7)
            }
        }
    }

    private fun summaryNum(text: String, color: java.awt.Color) = JLabel(text).apply {
        foreground = color; font = font.deriveFont(java.awt.Font.BOLD, 11f)
    }

    private fun summaryMuted(text: String) = JLabel(text).apply {
        foreground = colTextMuted; font = font.deriveFont(10f)
    }

    private fun summaryPct(text: String, fg: java.awt.Color, bg: java.awt.Color): JLabel {
        return object : JLabel(text) {
            init {
                foreground = fg; font = font.deriveFont(java.awt.Font.BOLD, 10f)
                border = BorderFactory.createEmptyBorder(1, 5, 1, 5); isOpaque = false
            }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = bg; g2.fillRoundRect(0, 0, width, height, 6, 6)
                super.paintComponent(g)
            }
        }
    }

    private fun summaryInsertDeleteBar(insertions: Int, deletions: Int): JPanel {
        return object : JPanel() {
            init {
                preferredSize = java.awt.Dimension(72, 4)
                maximumSize = java.awt.Dimension(120, 4)
                isOpaque = false
            }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                val w = width
                val h = height
                g2.color = colBgElevated
                g2.fillRoundRect(0, 0, w, h, h, h)
                val total = insertions + deletions
                if (total <= 0) return
                var gw = ((w.toLong() * insertions) / total).toInt().coerceIn(0, w)
                var rw = ((w.toLong() * deletions) / total).toInt().coerceIn(0, w - gw)
                val pad = w - gw - rw
                if (pad > 0) {
                    if (insertions >= deletions) gw += pad else rw += pad
                }
                if (gw > 0) {
                    g2.color = colHuman
                    g2.fillRoundRect(0, 0, gw, h, h / 2, h / 2)
                }
                if (rw > 0) {
                    g2.color = colDelete
                    g2.fillRoundRect(gw, 0, rw, h, h / 2, h / 2)
                }
            }
        }
    }

    private fun summarySegBar(aiRatio: Double, humanRatio: Double): JPanel {
        return object : JPanel() {
            init { preferredSize = java.awt.Dimension(120, 4); maximumSize = java.awt.Dimension(200, 4); isOpaque = false }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                val w = width; val h = height
                g2.color = colBgElevated; g2.fillRoundRect(0, 0, w, h, h, h)
                val aiW = (w * aiRatio).toInt()
                val humanW = (w * humanRatio).toInt()
                var x = 0
                if (aiW > 0) {
                    g2.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(0x3A, 0x8F, 0xD4), aiW.toFloat(), 0f, java.awt.Color(0x6E, 0xC0, 0xF5))
                    g2.fillRoundRect(x, 0, aiW, h, h / 2, h / 2); x += aiW + 1
                }
                if (humanW > 0) {
                    g2.color = colHuman; g2.fillRoundRect(x, 0, humanW, h, h / 2, h / 2)
                }
            }
        }
    }

    private fun changesRoundedCard(content: JComponent): JPanel {
        return object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                border = BorderFactory.createEmptyBorder(12, 14, 14, 14)
                add(content, BorderLayout.CENTER)
            }

            override fun getMaximumSize(): java.awt.Dimension =
                java.awt.Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)

            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = colBgSecondary
                g2.fillRoundRect(0, 0, width, height, 12, 12)
                g2.color = colBorder
                g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
            }
        }
    }

    private fun mutedRow(text: String): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(2, 8, 6, 8)
            add(
                JLabel(text).apply {
                    foreground = colTextMuted
                    font = font.deriveFont(10f)
                },
                BorderLayout.WEST
            )
        }
    }

    private fun sessionRow(e: BranchSessionListEntry): JPanel {
        val (statusLabel, statusColor) = when (e.status) {
            HomeBranchSession.STATUS_OPEN -> "Open" to colHuman
            HomeBranchSession.STATUS_STASHED -> "Stashed" to colAi
            HomeBranchSession.STATUS_CLOSED -> "Closed" to colTextMuted
            else -> e.status to colTextSecondary
        }
        val idShort = e.sessionId.take(8)
        val opened = e.openedAt.take(10).ifEmpty { "\u2014" }
        val updated = e.updatedAt.take(10).ifEmpty { "\u2014" }
        val commitPart = e.commitSha?.take(8)?.let { " \u00b7 $it" } ?: ""
        val stashPart = if (e.stashLinkCount > 0) " \u00b7 stash\u00d7${e.stashLinkCount}" else ""

        val badge = JLabel(" $statusLabel ").apply {
            foreground = statusColor
            font = font.deriveFont(java.awt.Font.BOLD, 9f)
            border = BorderFactory.createEmptyBorder(1, 4, 1, 4)
            isOpaque = true
            background = withAlpha(statusColor, 35)
        }
        val detail = JLabel(
            "<html><font color='${hex(colTextPrimary)}'>$idShort</font>" +
                "<font color='${hex(colTextMuted)}'> \u00b7 opened $opened \u00b7 updated $updated$commitPart$stashPart</font></html>"
        ).apply { font = font.deriveFont(10f) }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            preferredSize = java.awt.Dimension(0, 28)
            maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), 28)
            border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
            val west = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
            west.add(badge)
            west.add(detail)
            add(west, BorderLayout.WEST)
            val sha = e.commitSha
            if (!sha.isNullOrBlank()) {
                val showChanges = JLabel("Show changes").apply {
                    foreground = colAi
                    font = font.deriveFont(10f)
                    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                    toolTipText = "Open a read-only git show summary for $sha"
                    addMouseListener(object : java.awt.event.MouseAdapter() {
                        override fun mouseClicked(ev: java.awt.event.MouseEvent) {
                            ai.blamely.actions.ShowSessionChangesAction.showFor(project, sha)
                        }
                    })
                }
                val east = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
                east.add(showChanges)
                add(east, BorderLayout.EAST)
            }
        }
    }

    private fun branchBadge(name: String): JLabel {
        return object : JLabel(name) {
            init {
                foreground = colTextPrimary
                font = font.deriveFont(java.awt.Font.BOLD, 10f)
                border = BorderFactory.createEmptyBorder(1, 6, 1, 6)
                isOpaque = false
            }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = withAlpha(colAi, 40)
                g2.fillRoundRect(0, 0, width, height, 6, 6)
                g2.color = withAlpha(colAi, 80)
                g2.drawRoundRect(0, 0, width - 1, height - 1, 6, 6)
                super.paintComponent(g)
            }
        }
    }

    // ── Group row ──

    private fun groupRow(title: String, count: Int): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            preferredSize = java.awt.Dimension(0, 28)
            maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), 28)
            border = BorderFactory.createEmptyBorder(0, 0, 4, 0)
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
            left.add(JLabel(AllIcons.Nodes.Folder).apply { alignmentY = java.awt.Component.CENTER_ALIGNMENT })
            left.add(JLabel(title).apply { foreground = colTextPrimary; font = font.deriveFont(java.awt.Font.BOLD, 12f) })
            add(left, BorderLayout.WEST)
            val badge = object : JLabel("$count") {
                init {
                    foreground = colTextMuted
                    font = font.deriveFont(10f)
                    border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
                    isOpaque = false
                }

                override fun paintComponent(g: java.awt.Graphics) {
                    val g2 = g as java.awt.Graphics2D
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = colBgElevated
                    g2.fillRoundRect(0, 0, width, height, 10, 10)
                    g2.color = colBorderSubtle
                    g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
                    super.paintComponent(g)
                }
            }
            add(badge, BorderLayout.EAST)
        }
    }

    // ── File row ──

    private fun fileRow(
        name: String,
        path: String,
        aiChars: Int,
        humanChars: Int,
        aiLines: Int,
        humanLines: Int,
        aiPct: Double,
        gitInsertions: Int,
        gitDeletions: Int
    ): JPanel {
        val total = aiChars + humanChars
        val humanPct = if (total > 0) 100.0 - aiPct else 0.0
        val dotIdx = name.lastIndexOf('.')
        val baseName = if (dotIdx > 0) name.substring(0, dotIdx) else name
        val ext = if (dotIdx > 0) name.substring(dotIdx) else ""

        val row = object : JPanel(BorderLayout()) {
            init {
                isOpaque = false; cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                preferredSize = java.awt.Dimension(0, 30)
                maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), 30)
                border = BorderFactory.createEmptyBorder(6, 4, 6, 8)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) return
                        if (e.clickCount != 1) return
                        openFile(path)
                    }
                    override fun mouseEntered(e: java.awt.event.MouseEvent) { background = colBgHover; isOpaque = true; repaint() }
                    override fun mouseExited(e: java.awt.event.MouseEvent) { isOpaque = false; repaint() }
                })
            }
        }

        val nameLabel = JLabel("<html><font color='${hex(colTextPrimary)}'>${baseName}</font><font color='${hex(colTextMuted)}'>${ext}</font></html>").apply {
            font = font.deriveFont(11f); preferredSize = java.awt.Dimension(120, 20)
        }
        row.add(nameLabel, BorderLayout.WEST)

        // Stats
        val statsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        statsPanel.add(JLabel("AI:").apply { foreground = colTextMuted; font = font.deriveFont(10f) })
        statsPanel.add(JLabel("$aiChars").apply { foreground = colAi; font = font.deriveFont(java.awt.Font.BOLD, 10f) })
        statsPanel.add(JLabel("\u00a9 $aiLines").apply { foreground = colTextMuted; font = font.deriveFont(10f) })
        statsPanel.add(summaryPct("${"%.0f".format(aiPct)}%", colAi, colAiBg))
        statsPanel.add(JLabel("|").apply { foreground = colBorder })
        statsPanel.add(JLabel("Human:").apply { foreground = colTextMuted; font = font.deriveFont(10f) })
        statsPanel.add(JLabel("$humanChars").apply { foreground = colHuman; font = font.deriveFont(java.awt.Font.BOLD, 10f) })
        statsPanel.add(JLabel("\u00a9 $humanLines").apply { foreground = colTextMuted; font = font.deriveFont(10f) })
        statsPanel.add(summaryPct("${"%.0f".format(humanPct)}%", colHuman, colHumanBg))

        // Mini bar (AI share of attribution)
        val miniBar = object : JPanel() {
            init { preferredSize = java.awt.Dimension(60, 3); isOpaque = false }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = colBgElevated; g2.fillRoundRect(0, 0, width, height, height, height)
                val fill = (width * (aiPct / 100.0).coerceIn(0.0, 1.0)).toInt()
                if (fill > 0) {
                    g2.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(0x3A, 0x8F, 0xD4), fill.toFloat(), 0f, java.awt.Color(0x6E, 0xC0, 0xF5))
                    g2.fillRoundRect(0, 0, fill, height, height, height)
                }
            }
        }
        statsPanel.add(miniBar)

        if (gitInsertions > 0 || gitDeletions > 0) {
            statsPanel.add(JLabel("|").apply { foreground = colBorder })
            statsPanel.add(JLabel("Δ").apply { foreground = colTextMuted; font = font.deriveFont(10f) })
            statsPanel.add(JLabel("+${gitInsertions}").apply { foreground = colHuman; font = font.deriveFont(java.awt.Font.BOLD, 10f) })
            statsPanel.add(JLabel("\u2212${gitDeletions}").apply { foreground = colDelete; font = font.deriveFont(java.awt.Font.BOLD, 10f) })
            val fileDiffBar = object : JPanel() {
                init { preferredSize = java.awt.Dimension(48, 3); isOpaque = false }
                override fun paintComponent(g: java.awt.Graphics) {
                    val g2 = g as java.awt.Graphics2D
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    val w = width
                    val h = height
                    g2.color = colBgElevated
                    g2.fillRoundRect(0, 0, w, h, h, h)
                    val t = gitInsertions + gitDeletions
                    if (t <= 0) return
                    var gw = ((w.toLong() * gitInsertions) / t).toInt().coerceIn(0, w)
                    var rw = ((w.toLong() * gitDeletions) / t).toInt().coerceIn(0, w - gw)
                    val pad = w - gw - rw
                    if (pad > 0) {
                        if (gitInsertions >= gitDeletions) gw += pad else rw += pad
                    }
                    if (gw > 0) {
                        g2.color = colHuman
                        g2.fillRoundRect(0, 0, gw, h, h / 2, h / 2)
                    }
                    if (rw > 0) {
                        g2.color = colDelete
                        g2.fillRoundRect(gw, 0, rw, h, h / 2, h / 2)
                    }
                }
            }
            statsPanel.add(fileDiffBar)
        }

        row.add(statsPanel, BorderLayout.CENTER)

        row.toolTipText = buildString {
            append("Open $path")
            if (gitInsertions > 0 || gitDeletions > 0) {
                append(" — Git vs HEAD: +").append(gitInsertions).append(" / −").append(gitDeletions)
            }
        }
        return row
    }

    private fun openFile(filePath: String) {
        val base = basePath
        val vf = LocalFileSystem.getInstance().findFileByPath("$base/$filePath".replace("//", "/"))
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    private fun isPathUnderProject(basePath: String, filePath: String): Boolean {
        val normalized = filePath.replace('\\', '/')
        if (normalized.contains("..") || normalized.startsWith("/")) return false
        val file = File(basePath, normalized)
        return try {
            file.exists() && file.canonicalPath.startsWith(File(basePath).canonicalPath)
        } catch (_: Exception) {
            false
        }
    }
}

// ─── Overall Changes (from committed git notes) ─────────────────────────────

private class OverallChangesPanel(private val project: Project) : JPanel(BorderLayout()) {

    companion object {
        /** Fixed-ish History commit grid: AI column width (bar + %). */
        private const val COMMIT_COL_AI_W = 172
        private const val COMMIT_COL_HASH_W = 56
        private const val COMMIT_COL_AUTHOR_W = 140
        private const val COMMIT_COL_ADDDEL_W = 44
        private const val COMMIT_COL_CODING_W = 76
        private const val COMMIT_COL_BRANCH_W = 92
        /** Scroll viewport height for commit list (rows scroll inside). */
        private const val COMMIT_TABLE_VIEWPORT_H = 312
    }

    private val refreshAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)
    private val contentPanel = object : JPanel(), javax.swing.Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(18, 18, 22, 18)
        }
        override fun getPreferredScrollableViewportSize(): java.awt.Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = 16
        override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = visibleRect.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    init {
        val scroll = JScrollPane(contentPanel).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBar.unitIncrement = 16
        }
        add(scroll, BorderLayout.CENTER)
        ApplicationManager.getApplication().executeOnPooledThread {
            val data = loadOverallData()
            ApplicationManager.getApplication().invokeLater { rebuild(data) }
        }
        project.messageBus.connect(project).subscribe(BlameUpdateListener.TOPIC, object : BlameUpdateListener {
            override fun blameUpdated() {
                refreshAlarm.cancelAllRequests()
                refreshAlarm.addRequest(
                    {
                        val data = loadOverallData()
                        ApplicationManager.getApplication().invokeLater { rebuild(data) }
                    },
                    2000
                )
            }
        })
    }

    // ── data ──

    private data class ModelDetail(
        var totalLines: Int = 0,
        var commitCount: Int = 0,
        val interactionTypes: MutableSet<String> = mutableSetOf(),
        val commitShas: MutableList<String> = mutableListOf()
    )

    private data class OverallData(
        val commits: List<CommitReport>,
        val totalAiLines: Int,
        val totalHumanLines: Int,
        val totalDeleted: Int,
        val totalFiles: Int,
        val modelDetails: Map<String, ModelDetail>,
        val totalWaitingMs: Long,
        val allInteractionTypes: Set<String>,
        val totalCodingTimeMs: Long = 0
    )

    // ── load ──

    private fun loadOverallData(): OverallData {
        val empty = OverallData(emptyList(), 0, 0, 0, 0, emptyMap(), 0, emptySet(), 0)
        val cwd = GitUtils.getRepoRoot(project) ?: project.basePath ?: return empty

        val logOut = GitUtils.run(cwd, "log", "--format=%H%n%aN%n%ar", "--max-count=50")
            ?: return empty
        val logLines = logOut.lines().filter { it.isNotBlank() }
        data class GitCommitInfo(val sha: String, val author: String, val date: String)
        val gitInfos = mutableListOf<GitCommitInfo>()
        var i = 0
        while (i + 2 < logLines.size) {
            gitInfos.add(GitCommitInfo(logLines[i], logLines[i + 1], logLines[i + 2]))
            i += 3
        }

        val commits = mutableListOf<CommitReport>()
        val globalModelDetails = mutableMapOf<String, ModelDetail>()
        val allInteractions = mutableSetOf<String>()
        var totalAi = 0; var totalHuman = 0; var totalDel = 0; var totalFiles = 0; var totalWait = 0L; var totalCoding = 0L

        for (info in gitInfos) {
            val note = GitUtils.getNoteContent(cwd, info.sha) ?: continue
            val report = parseReport(note, info.author, info.date) ?: continue
            commits.add(report)
            totalAi += report.aiLinesAdded
            totalHuman += report.humanLinesAdded
            totalDel += report.totalLinesDeleted
            totalFiles += report.totalFilesChanged
            totalWait += report.timeWaitingForAiMs
            totalCoding += report.codingTimeMs
            allInteractions.addAll(report.interactionTypes.filter { it != "unknown" })

            val validModels = report.models.filter { it != "unknown" }
            val aiPerModel = if (validModels.isNotEmpty()) report.aiLinesAdded / validModels.size else 0
            val aiRemainder = if (validModels.isNotEmpty()) report.aiLinesAdded % validModels.size else 0
            for ((mi, m) in validModels.withIndex()) {
                val d = globalModelDetails.getOrPut(m) { ModelDetail() }
                d.totalLines += aiPerModel + if (mi < aiRemainder) 1 else 0
                d.commitShas.add(report.commitHash.take(8))
                d.interactionTypes.addAll(report.interactionTypes.filter { it != "unknown" })
                d.commitCount++
            }
        }
        return OverallData(commits, totalAi, totalHuman, totalDel, totalFiles, globalModelDetails, totalWait, allInteractions, totalCoding)
    }

    private fun parseReport(note: String, author: String = "", authorDate: String = ""): CommitReport? {
        val lines = note.lines()
        fun yamlVal(key: String): String? {
            for (line in lines) {
                val t = line.trim()
                if (t.startsWith("$key:")) return t.removePrefix("$key:").trim().removeSurrounding("\"")
            }
            return null
        }
        val commitHash = yamlVal("commit_hash") ?: return null
        val commitMessage = yamlVal("commit_message") ?: ""
        val branch = yamlVal("branch") ?: ""
        val generatedAt = yamlVal("commitDate") ?: yamlVal("generated_at") ?: ""
        val totalFilesChanged = yamlVal("total_files_changed")?.toIntOrNull() ?: 0
        val totalLinesAdded = yamlVal("total_lines_added")?.toIntOrNull() ?: 0
        val totalLinesDeleted = yamlVal("total_lines_deleted")?.toIntOrNull() ?: 0
        val aiLinesAdded = yamlVal("ai_lines_added")?.toIntOrNull() ?: 0
        val humanLinesAdded = yamlVal("human_lines_added")?.toIntOrNull() ?: 0
        val aiPercentage = yamlVal("ai_percentage") ?: "0%"
        val timeWaitingForAiMs = yamlVal("time_waiting_for_ai_ms")?.toLongOrNull() ?: 0
        val firstStartCodingTime = yamlVal("first_start_coding_time") ?: ""

        var codingTimeMs = 0L
        if (firstStartCodingTime.isNotBlank() && firstStartCodingTime != "null" && generatedAt.isNotBlank()) {
            try {
                val start = java.time.Instant.parse(firstStartCodingTime)
                val end = java.time.Instant.parse(generatedAt)
                codingTimeMs = java.time.Duration.between(start, end).toMillis().coerceAtLeast(0)
            } catch (_: Exception) {}
        }

        val models = mutableListOf<String>()
        val interactionTypes = mutableListOf<String>()
        var inModels = false; var inInteraction = false
        for (line in lines) {
            val t = line.trim()
            when {
                t == "models:" -> { inModels = true; inInteraction = false; continue }
                t == "interaction_types:" -> { inInteraction = true; inModels = false; continue }
                t.endsWith(":") && !t.startsWith("-") -> { inModels = false; inInteraction = false }
            }
            if (inModels && t.startsWith("- ")) models.add(t.removePrefix("- ").removeSurrounding("\"").trim())
            if (inInteraction && t.startsWith("- ")) interactionTypes.add(t.removePrefix("- ").removeSurrounding("\"").trim())
        }
        return CommitReport(commitHash, commitMessage, branch, generatedAt, author, authorDate, totalFilesChanged, totalLinesAdded, totalLinesDeleted, aiLinesAdded, humanLinesAdded, aiPercentage, models, interactionTypes, timeWaitingForAiMs, firstStartCodingTime, codingTimeMs)
    }

    /** Distributes a commit's AI line count across named models (same heuristic as [loadOverallData] aggregation). */
    private fun modelLineSharesForCommit(report: CommitReport): List<Pair<String, Int>> {
        val validModels = report.models.filter { it != "unknown" }
        if (validModels.isEmpty() || report.aiLinesAdded <= 0) return emptyList()
        val base = report.aiLinesAdded / validModels.size
        val remainder = report.aiLinesAdded % validModels.size
        return validModels.take(3).mapIndexed { mi, name ->
            name to (base + if (mi < remainder) 1 else 0)
        }
    }

    // ── theme colors (matching HTML design) ──

    private val colBgSecondary get() = java.awt.Color(0x2B, 0x2D, 0x30)
    private val colBgElevated get() = java.awt.Color(0x31, 0x34, 0x38)
    private val colBorder get() = java.awt.Color(0x3D, 0x40, 0x45)
    private val colBorderSubtle get() = java.awt.Color(0x2E, 0x30, 0x34)
    private val colTextPrimary get() = java.awt.Color(0xDF, 0xE1, 0xE5)
    private val colTextSecondary get() = java.awt.Color(0x9D, 0xA0, 0xA8)
    private val colTextMuted get() = java.awt.Color(0x6B, 0x6F, 0x76)
    private val colAi get() = java.awt.Color(0x4D, 0x9D, 0xE0)
    private val colHuman get() = java.awt.Color(0x56, 0xA0, 0x64)
    private val colDelete get() = java.awt.Color(0xE0, 0x6C, 0x75)
    private val colOrange get() = java.awt.Color(0xE5, 0x94, 0x3A)
    private val colPurple get() = java.awt.Color(0x9E, 0x7B, 0xC4)
    /** Alternating commit row background inside History table. */
    private val colRowStripe get() = java.awt.Color(0x27, 0x29, 0x2E)

    private val rankColors = listOf(colAi, colHuman, colOrange)

    private fun hex(c: java.awt.Color) = "#%02X%02X%02X".format(c.red, c.green, c.blue)
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun withAlpha(c: java.awt.Color, a: Int) = java.awt.Color(c.red, c.green, c.blue, a)

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            s > 0 -> "${s}s"
            else -> "${ms}ms"
        }
    }

    private fun historyHeroPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
            maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), 56)
            border = BorderFactory.createEmptyBorder(0, 2, 8, 2)
            val col = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
            col.add(JLabel("Commit history").apply {
                foreground = colTextPrimary
                font = font.deriveFont(java.awt.Font.BOLD, 14f)
            })
            col.add(Box.createVerticalStrut(4))
            col.add(JLabel("From git notes · refs/notes/blamely").apply {
                foreground = colTextMuted
                font = font.deriveFont(11f)
            })
            add(col, BorderLayout.WEST)
        }
    }

    // ── reusable ui builders ──

    private fun spacer(h: Int) = Box.createRigidArea(java.awt.Dimension(0, h)).apply {
        (this as JComponent).alignmentX = LEFT_ALIGNMENT
    }

    private fun htmlLabel(html: String, fg: java.awt.Color = colTextPrimary): JLabel = JLabel("<html>$html</html>").apply {
        alignmentX = LEFT_ALIGNMENT
        foreground = fg
    }

    private fun metaChip(text: String, dotColor: java.awt.Color): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            isOpaque = false; alignmentX = LEFT_ALIGNMENT
            add(object : JPanel() {
                init { preferredSize = java.awt.Dimension(6, 6); isOpaque = false }
                override fun paintComponent(g: java.awt.Graphics) {
                    val g2 = g as java.awt.Graphics2D
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = dotColor; g2.fillOval(0, 0, 6, 6)
                }
            })
            add(JLabel(text).apply { foreground = colTextSecondary; font = font.deriveFont(11f) })
        }
    }

    private fun statBadge(text: String, color: java.awt.Color): JLabel {
        return object : JLabel(text) {
            init {
                foreground = color; font = font.deriveFont(java.awt.Font.BOLD, 12f)
                border = BorderFactory.createEmptyBorder(3, 10, 3, 10)
                isOpaque = false
            }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = withAlpha(color, 40)
                g2.fillRoundRect(0, 0, width, height, height, height)
                g2.color = withAlpha(color, 60)
                g2.drawRoundRect(0, 0, width - 1, height - 1, height, height)
                super.paintComponent(g)
            }
        }
    }

    private fun segmentedBar(aiRatio: Double, humanRatio: Double, barHeight: Int = 6): JPanel {
        return object : JPanel() {
            init {
                alignmentX = LEFT_ALIGNMENT
                preferredSize = java.awt.Dimension(200, barHeight)
                maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), barHeight)
                isOpaque = false
            }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                val h = height; val w = width
                g2.color = colBgElevated
                g2.fillRoundRect(0, 0, w, h, h, h)
                val gap = 2
                val aiW = ((w - gap) * aiRatio).toInt()
                val humanW = ((w - gap) * humanRatio).toInt()
                var x = 0
                if (aiW > 0) {
                    g2.paint = java.awt.GradientPaint(x.toFloat(), 0f, java.awt.Color(0x3A, 0x8F, 0xD4), (x + aiW).toFloat(), 0f, java.awt.Color(0x5B, 0xAE, 0xE8))
                    g2.fillRoundRect(x, 0, aiW, h, h / 2, h / 2)
                    x += aiW + gap
                }
                if (humanW > 0) {
                    g2.paint = java.awt.GradientPaint(x.toFloat(), 0f, java.awt.Color(0x4A, 0x94, 0x58), (x + humanW).toFloat(), 0f, java.awt.Color(0x63, 0xBA, 0x72))
                    g2.fillRoundRect(x, 0, humanW, h, h / 2, h / 2)
                }
            }
        }
    }

    /** Green / red bar for commit insert vs delete line counts (History overview). */
    private fun historyInsertDeleteBar(insertions: Int, deletions: Int, barHeight: Int = 5): JPanel {
        return object : JPanel() {
            init {
                alignmentX = LEFT_ALIGNMENT
                preferredSize = java.awt.Dimension(200, barHeight)
                maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), barHeight)
                isOpaque = false
            }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                val h = height
                val w = width
                g2.color = colBgElevated
                g2.fillRoundRect(0, 0, w, h, h, h)
                val total = insertions + deletions
                if (total <= 0) return
                var gw = ((w.toLong() * insertions) / total).toInt().coerceIn(0, w)
                var rw = ((w.toLong() * deletions) / total).toInt().coerceIn(0, w - gw)
                val pad = w - gw - rw
                if (pad > 0) {
                    if (insertions >= deletions) gw += pad else rw += pad
                }
                if (gw > 0) {
                    g2.color = colHuman
                    g2.fillRoundRect(0, 0, gw, h, h / 2, h / 2)
                }
                if (rw > 0) {
                    g2.color = colDelete
                    g2.fillRoundRect(gw, 0, rw, h, h / 2, h / 2)
                }
            }
        }
    }

    private fun gradientBar(ratio: Double, rankIdx: Int, barHeight: Int = 4): JPanel {
        val startColors = listOf(java.awt.Color(0x3A, 0x8F, 0xD4), java.awt.Color(0x4A, 0x94, 0x58), java.awt.Color(0xC9, 0x7A, 0x28))
        val endColors = listOf(java.awt.Color(0x7E, 0xC8, 0xF8), java.awt.Color(0x7A, 0xD4, 0x85), java.awt.Color(0xE8, 0xB0, 0x60))
        return object : JPanel() {
            init {
                alignmentX = LEFT_ALIGNMENT
                preferredSize = java.awt.Dimension(200, barHeight)
                maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), barHeight)
                isOpaque = false
            }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = colBgElevated
                g2.fillRoundRect(0, 0, width, height, height, height)
                val fill = (width * ratio.coerceIn(0.0, 1.0)).toInt()
                if (fill > 0) {
                    val idx = rankIdx.coerceIn(0, startColors.size - 1)
                    g2.paint = java.awt.GradientPaint(0f, 0f, startColors[idx], fill.toFloat(), 0f, endColors[idx])
                    g2.fillRoundRect(0, 0, fill, height, height, height)
                }
            }
        }
    }

    /** Hairline AI % bar — minimal vertical footprint for History commit rows. */
    private fun historyCommitAiBar(pct: Double, barHeight: Int = 2): JPanel {
        return object : JPanel() {
            init {
                alignmentX = LEFT_ALIGNMENT
                alignmentY = CENTER_ALIGNMENT
                preferredSize = java.awt.Dimension(100, barHeight)
                maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), barHeight)
                minimumSize = java.awt.Dimension(48, barHeight)
                isOpaque = false
            }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g.create() as java.awt.Graphics2D
                try {
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    val h = height
                    val w = width
                    val arc = h.coerceAtMost(w)
                    g2.color = colBgElevated
                    g2.fillRoundRect(0, 0, w, h, arc, arc)
                    val ratio = (pct / 100.0).coerceIn(0.0, 1.0)
                    val fill = (w * ratio).toInt().coerceIn(0, w)
                    if (fill > 0) {
                        val clipShape = java.awt.geom.RoundRectangle2D.Float(0f, 0f, w.toFloat(), h.toFloat(), arc.toFloat(), arc.toFloat())
                        g2.clip = clipShape
                        val c0 = java.awt.Color(0x3A, 0x8F, 0xD4)
                        val c1 = java.awt.Color(0x6D, 0xC5, 0xFB)
                        g2.paint = java.awt.GradientPaint(0f, 0f, c0, fill.toFloat(), 0f, c1)
                        g2.fillRect(0, 0, fill, h)
                        g2.clip = null
                    }
                    g2.color = colBorderSubtle
                    g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc)
                } finally {
                    g2.dispose()
                }
            }
        }
    }

    /** AI %: hairline bar + label on one row — no extra vertical padding from the bar. */
    private fun miniBarCell(pct: Double): JPanel {
        val bh = 2
        val bar = historyCommitAiBar(pct, bh)
        val pctLb = JLabel("${"%.1f".format(pct)}%").apply {
            foreground = colAi
            font = font.deriveFont(java.awt.Font.BOLD, 10f)
            horizontalAlignment = SwingConstants.RIGHT
            preferredSize = java.awt.Dimension(42, 14)
            alignmentY = CENTER_ALIGNMENT
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(bar)
            add(Box.createHorizontalStrut(6))
            add(pctLb)
            preferredSize = java.awt.Dimension(COMMIT_COL_AI_W, 16)
            maximumSize = java.awt.Dimension(COMMIT_COL_AI_W, 20)
        }
    }

    private fun tagLabel(text: String, color: java.awt.Color): JLabel {
        return object : JLabel(text) {
            init {
                foreground = color; font = font.deriveFont(10f)
                border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
                isOpaque = false
            }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = withAlpha(color, 30)
                g2.fillRoundRect(0, 0, width, height, 6, 6)
                g2.color = withAlpha(color, 50)
                g2.drawRoundRect(0, 0, width - 1, height - 1, 6, 6)
                super.paintComponent(g)
            }
        }
    }

    private fun sectionLabel(text: String): JPanel {
        return JPanel(BorderLayout()).apply {
            alignmentX = LEFT_ALIGNMENT; isOpaque = false
            maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), 16)
            border = BorderFactory.createEmptyBorder(0, 2, 0, 0)
            add(JLabel(text).apply {
                foreground = colTextMuted
                font = font.deriveFont(java.awt.Font.BOLD, 11f)
            }, BorderLayout.WEST)
            add(object : JPanel() {
                init {
                    isOpaque = false
                    border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
                }
                override fun paintComponent(g: java.awt.Graphics) {
                    val g2 = g as java.awt.Graphics2D
                    g2.color = colBorderSubtle
                    g2.fillRect(0, height / 2, width, 1)
                }
            }, BorderLayout.CENTER)
        }
    }

    private fun roundedCard(inner: JComponent): JPanel {
        return object : JPanel(BorderLayout()) {
            init {
                alignmentX = LEFT_ALIGNMENT; isOpaque = false
                border = BorderFactory.createEmptyBorder(16, 18, 16, 18)
                maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
                add(inner, BorderLayout.CENTER)
            }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = colBgSecondary
                g2.fillRoundRect(0, 0, width, height, 12, 12)
                g2.color = colBorder
                g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
            }
        }
    }

    private fun modelCard(inner: JComponent, rankIdx: Int): JPanel {
        val accentColor = rankColors.getOrElse(rankIdx) { colAi }
        return object : JPanel(BorderLayout()) {
            init {
                alignmentX = LEFT_ALIGNMENT; isOpaque = false
                border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
                maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
                add(inner, BorderLayout.CENTER)
            }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = colBgSecondary; g2.fillRoundRect(0, 0, width, height, 6, 6)
                g2.color = colBorderSubtle; g2.drawRoundRect(0, 0, width - 1, height - 1, 6, 6)
                g2.color = accentColor; g2.fillRoundRect(0, 0, 3, height, 2, 2)
            }
        }
    }

    private fun rankBadge(rank: Int): JLabel {
        val color = rankColors.getOrElse(rank - 1) { colAi }
        return object : JLabel("#$rank") {
            init {
                foreground = color; font = font.deriveFont(java.awt.Font.BOLD, 9f)
                preferredSize = java.awt.Dimension(18, 18)
                horizontalAlignment = CENTER; isOpaque = false
            }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = withAlpha(color, 45); g2.fillRoundRect(0, 0, width, height, 4, 4)
                super.paintComponent(g)
            }
        }
    }

    // ── rebuild ──

    private fun rebuild(data: OverallData) {
        if (project.isDisposed) return
        contentPanel.removeAll()

        if (data.commits.isEmpty()) {
            contentPanel.add(historyHeroPanel())
            contentPanel.add(spacer(16))
            contentPanel.add(htmlLabel("<center><font color='${hex(colTextMuted)}'>No Blamely reports found in git notes.</font></center>"))
            contentPanel.revalidate(); contentPanel.repaint(); return
        }

        // Overview reflects HEAD (newest commit): git log order is newest-first; aggregated totals
        // across all commits mislead right after a commit when the user expects "this commit".
        val latest = data.commits.first()
        val totalAddOnly = latest.aiLinesAdded + latest.humanLinesAdded
        val aiPctAdd = if (totalAddOnly > 0) (100.0 * latest.aiLinesAdded / totalAddOnly) else 0.0
        val humanPctAdd = if (totalAddOnly > 0) (100.0 * latest.humanLinesAdded / totalAddOnly) else 0.0

        contentPanel.add(historyHeroPanel())
        contentPanel.add(spacer(14))

        // ── Overview Card ──
        val overviewInner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
        }

        overviewInner.add(JLabel("Latest commit (${latest.commitHash.take(7)})").apply {
            foreground = colTextSecondary
            font = font.deriveFont(11f)
            toolTipText = latest.commitMessage.ifBlank { latest.commitHash }
        })
        overviewInner.add(spacer(8))

        // Combined row: stat badges LEFT, meta chips RIGHT
        val combinedRow = JPanel(BorderLayout()).apply { isOpaque = false; alignmentX = LEFT_ALIGNMENT; maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), 30) }
        val statsLeft = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply { isOpaque = false }
        statsLeft.add(statBadge("${latest.aiLinesAdded} AI lines \u00b7 ${"%.1f".format(aiPctAdd)}%", colAi))
        statsLeft.add(statBadge("${latest.humanLinesAdded} Human \u00b7 ${"%.1f".format(humanPctAdd)}%", colHuman))
        combinedRow.add(statsLeft, BorderLayout.WEST)
        val chipsRight = JPanel(FlowLayout(FlowLayout.RIGHT, 12, 0)).apply { isOpaque = false }
        chipsRight.add(metaChip("${data.commits.size} in history", colOrange))
        chipsRight.add(metaChip("${latest.totalFilesChanged} files", colAi))
        if (latest.codingTimeMs > 0) chipsRight.add(metaChip("Coding: ${formatDuration(latest.codingTimeMs)}", colHuman))
        if (latest.timeWaitingForAiMs > 0) chipsRight.add(metaChip("AI wait: ${formatDuration(latest.timeWaitingForAiMs)}", colPurple))
        combinedRow.add(chipsRight, BorderLayout.EAST)
        overviewInner.add(combinedRow)
        overviewInner.add(spacer(10))

        overviewInner.add(JLabel("AI vs Human (lines added)").apply {
            foreground = colTextMuted
            font = font.deriveFont(10f)
            alignmentX = LEFT_ALIGNMENT
        })
        overviewInner.add(spacer(4))
        overviewInner.add(segmentedBar(aiPctAdd / 100.0, humanPctAdd / 100.0, 6))

        overviewInner.add(spacer(10))
        val diffLegend = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        diffLegend.add(JLabel("Insert / delete (commit diff)").apply {
            foreground = colTextMuted
            font = font.deriveFont(10f)
        })
        diffLegend.add(JLabel("+${latest.totalLinesAdded}").apply {
            foreground = colHuman
            font = font.deriveFont(java.awt.Font.BOLD, 11f)
            toolTipText = "Lines inserted in this commit"
        })
        diffLegend.add(JLabel("insert").apply { foreground = colTextMuted; font = font.deriveFont(10f) })
        diffLegend.add(JLabel("|").apply { foreground = colBorder })
        diffLegend.add(JLabel("\u2212${latest.totalLinesDeleted}").apply {
            foreground = colDelete
            font = font.deriveFont(java.awt.Font.BOLD, 11f)
            toolTipText = "Lines deleted in this commit"
        })
        diffLegend.add(JLabel("delete").apply { foreground = colTextMuted; font = font.deriveFont(10f) })
        overviewInner.add(diffLegend)
        overviewInner.add(spacer(6))
        overviewInner.add(historyInsertDeleteBar(latest.totalLinesAdded, latest.totalLinesDeleted, 5))

        contentPanel.add(roundedCard(overviewInner))
        contentPanel.add(spacer(14))

        // ── AI Models (HEAD commit only; matches overview bar) ──
        val modelShares = modelLineSharesForCommit(latest)
        contentPanel.add(sectionLabel("AI Models (latest commit)"))
        contentPanel.add(spacer(7))

        if (modelShares.isNotEmpty()) {
            val totalModelLines = latest.aiLinesAdded.coerceAtLeast(1)
            for ((idx, share) in modelShares.withIndex()) {
                val (name, lineCount) = share
                val pct = "%.1f".format(100.0 * lineCount / totalModelLines)
                val ratio = lineCount.toDouble() / totalModelLines

                val inner = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }

                // Header row: rank badge + name + meta
                val headerRow = JPanel(BorderLayout(8, 0)).apply { isOpaque = false; alignmentX = LEFT_ALIGNMENT; maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), 20) }
                val leftRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
                leftRow.add(rankBadge(idx + 1))
                leftRow.add(JLabel(name).apply { foreground = colTextPrimary; font = font.deriveFont(java.awt.Font.BOLD, 12f) })
                headerRow.add(leftRow, BorderLayout.WEST)

                val metaRight = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply { isOpaque = false }
                metaRight.add(JLabel("$lineCount lines").apply { foreground = colTextSecondary; font = font.deriveFont(11f) })
                metaRight.add(JLabel("$pct%").apply { foreground = colTextSecondary; font = font.deriveFont(11f) })
                for (t in latest.interactionTypes.filter { it != "unknown" }.sorted()) {
                    metaRight.add(JLabel(t).apply { foreground = colTextMuted; font = font.deriveFont(11f) })
                }
                headerRow.add(metaRight, BorderLayout.EAST)

                inner.add(headerRow)
                inner.add(spacer(7))
                inner.add(gradientBar(ratio, idx, 4))

                contentPanel.add(modelCard(inner, idx))
                if (idx < modelShares.size - 1) contentPanel.add(spacer(7))
            }
        } else {
            contentPanel.add(htmlLabel("<font color='${hex(colTextMuted)}'>No AI models detected.</font>"))
        }
        contentPanel.add(spacer(14))

        // ── Commits table: fixed column header (scroll sync) + scrollable rows ──
        contentPanel.add(sectionLabel("Commits (${data.commits.size})"))
        contentPanel.add(spacer(7))

        fun commitHistoryGbc(col: Int): java.awt.GridBagConstraints {
            val c = java.awt.GridBagConstraints()
            c.gridx = col
            c.gridy = 0
            c.insets = java.awt.Insets(0, 0, 0, 8)
            c.fill = java.awt.GridBagConstraints.HORIZONTAL
            c.anchor = when (col) {
                3, 4, 6 -> java.awt.GridBagConstraints.EAST
                else -> java.awt.GridBagConstraints.WEST
            }
            c.weightx = if (col == 2) 1.0 else 0.0
            return c
        }

        fun fixCellW(w: Int): java.awt.Dimension = java.awt.Dimension(w, 22)

        val commitsRowsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

        val thFontBold = contentPanel.font.deriveFont(java.awt.Font.BOLD, 11f)

        val tableHeader = JPanel(java.awt.GridBagLayout()).apply {
            isOpaque = true
            background = colBgElevated
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        }
        fun addTh(text: String, col: Int, cellW: Int?, align: Int = SwingConstants.LEFT) {
            val lb = JLabel(text).apply {
                foreground = colTextMuted
                font = thFontBold
                horizontalAlignment = align
                verticalAlignment = SwingConstants.CENTER
                cellW?.let {
                    preferredSize = fixCellW(it)
                    minimumSize = fixCellW(it)
                    maximumSize = java.awt.Dimension(it, 28)
                }
            }
            tableHeader.add(lb, commitHistoryGbc(col))
        }
        addTh("HASH", 0, COMMIT_COL_HASH_W)
        addTh("AUTHOR", 1, COMMIT_COL_AUTHOR_W)
        addTh("MESSAGE", 2, null)
        addTh("+ADD", 3, COMMIT_COL_ADDDEL_W, SwingConstants.RIGHT)
        addTh("\u2212DEL", 4, COMMIT_COL_ADDDEL_W, SwingConstants.RIGHT)
        addTh("AI %", 5, COMMIT_COL_AI_W)
        addTh("CODING TIME", 6, COMMIT_COL_CODING_W, SwingConstants.RIGHT)
        addTh("BRANCH", 7, COMMIT_COL_BRANCH_W)

        for ((idx, report) in data.commits.withIndex()) {
            val sha = report.commitHash.take(7)
            val rawMsg = report.commitMessage.replace("\\n", " ").replace("\n", " ").trim()
            val msg = if (rawMsg.length > 80) rawMsg.take(80) + "\u2026" else rawMsg
            val aiPctNum = report.aiPercentage.removeSuffix("%").toDoubleOrNull() ?: 0.0

            val row = JPanel(java.awt.GridBagLayout()).apply {
                isOpaque = true
                alignmentX = LEFT_ALIGNMENT
                background = if (idx % 2 == 0) colBgSecondary else colRowStripe
                maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), 42)
                border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
            }

            val shaLb = JLabel(sha).apply {
                foreground = colAi
                font = font.deriveFont(java.awt.Font.BOLD, 11f)
                preferredSize = fixCellW(COMMIT_COL_HASH_W)
                minimumSize = fixCellW(COMMIT_COL_HASH_W)
                maximumSize = java.awt.Dimension(COMMIT_COL_HASH_W, 28)
            }
            row.add(shaLb, commitHistoryGbc(0))

            val authorDisplay = report.author.ifBlank { "—" }
            row.add(JLabel(authorDisplay).apply {
                foreground = colTextSecondary
                font = font.deriveFont(11f)
                preferredSize = fixCellW(COMMIT_COL_AUTHOR_W)
                minimumSize = fixCellW(COMMIT_COL_AUTHOR_W)
                maximumSize = java.awt.Dimension(COMMIT_COL_AUTHOR_W, 28)
                toolTipText = if (report.authorDate.isNotBlank()) "${report.author} \u00b7 ${report.authorDate}" else report.author
            }, commitHistoryGbc(1))

            row.add(JLabel(msg).apply {
                foreground = colTextPrimary
                font = font.deriveFont(12f)
                toolTipText = rawMsg
            }, commitHistoryGbc(2))

            row.add(JLabel("+${report.totalLinesAdded}").apply {
                foreground = colHuman
                font = font.deriveFont(11f)
                horizontalAlignment = SwingConstants.RIGHT
                preferredSize = fixCellW(COMMIT_COL_ADDDEL_W)
                minimumSize = fixCellW(COMMIT_COL_ADDDEL_W)
                maximumSize = java.awt.Dimension(COMMIT_COL_ADDDEL_W, 28)
                toolTipText = "Lines inserted"
            }, commitHistoryGbc(3))

            row.add(JLabel("\u2212${report.totalLinesDeleted}").apply {
                foreground = colDelete
                font = font.deriveFont(11f)
                horizontalAlignment = SwingConstants.RIGHT
                preferredSize = fixCellW(COMMIT_COL_ADDDEL_W)
                minimumSize = fixCellW(COMMIT_COL_ADDDEL_W)
                maximumSize = java.awt.Dimension(COMMIT_COL_ADDDEL_W, 28)
                toolTipText = "Lines deleted"
            }, commitHistoryGbc(4))

            row.add(miniBarCell(aiPctNum), commitHistoryGbc(5))

            val codingStr = if (report.codingTimeMs > 0) formatDuration(report.codingTimeMs) else "\u2014"
            row.add(JLabel(codingStr).apply {
                foreground = colTextSecondary
                font = font.deriveFont(11f)
                horizontalAlignment = SwingConstants.RIGHT
                preferredSize = fixCellW(COMMIT_COL_CODING_W)
                minimumSize = fixCellW(COMMIT_COL_CODING_W)
                maximumSize = java.awt.Dimension(COMMIT_COL_CODING_W, 28)
            }, commitHistoryGbc(6))

            val branchColor = if (report.branch == "master" || report.branch == "main") colPurple else colOrange
            val branchCell = JPanel(BorderLayout()).apply {
                isOpaque = false
                preferredSize = java.awt.Dimension(COMMIT_COL_BRANCH_W, 28)
                minimumSize = java.awt.Dimension(COMMIT_COL_BRANCH_W, 24)
                maximumSize = java.awt.Dimension(COMMIT_COL_BRANCH_W, 34)
                add(tagLabel(report.branch.ifBlank { "—" }, branchColor), BorderLayout.WEST)
            }
            row.add(branchCell, commitHistoryGbc(7))

            commitsRowsPanel.add(row)
        }

        val commitsScroll = JScrollPane(commitsRowsPanel).apply {
            border = BorderFactory.createEmptyBorder()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            setColumnHeaderView(tableHeader)
            columnHeader.background = colBgElevated
            viewport.background = colBgSecondary
            background = colBgSecondary
            preferredSize = java.awt.Dimension(0, COMMIT_TABLE_VIEWPORT_H)
            minimumSize = java.awt.Dimension(80, 120)
            alignmentX = LEFT_ALIGNMENT
        }

        /** Header + body share one viewport width so GridBag columns line up (Swing column header otherwise drifts). */
        fun syncCommitHistoryColumnWidths() {
            val w = commitsScroll.viewport.width.coerceAtLeast(1)
            if (w < 48) return
            val headerH = tableHeader.preferredSize.height.coerceIn(34, 44)
            tableHeader.minimumSize = Dimension(w, headerH)
            tableHeader.preferredSize = Dimension(w, headerH)
            tableHeader.maximumSize = Dimension(w, headerH)
            for (i in 0 until commitsRowsPanel.componentCount) {
                val rowPanel = commitsRowsPanel.getComponent(i) as? JPanel ?: continue
                val rh = rowPanel.preferredSize.height.coerceIn(38, 48)
                rowPanel.minimumSize = Dimension(w, rh)
                rowPanel.preferredSize = Dimension(w, rh)
                rowPanel.maximumSize = Dimension(w, rh)
            }
            commitsRowsPanel.revalidate()
            tableHeader.revalidate()
            commitsScroll.revalidate()
        }

        commitsScroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                syncCommitHistoryColumnWidths()
            }
        })
        commitsScroll.addComponentListener(object : ComponentAdapter() {
            override fun componentShown(e: ComponentEvent) {
                SwingUtilities.invokeLater { syncCommitHistoryColumnWidths() }
            }
        })

        val tableWrapper = object : JPanel(BorderLayout()) {
            init {
                alignmentX = LEFT_ALIGNMENT
                isOpaque = false
                add(commitsScroll, BorderLayout.CENTER)
            }
            override fun getMaximumSize(): java.awt.Dimension {
                val pref = preferredSize
                return java.awt.Dimension(Short.MAX_VALUE.toInt(), pref.height)
            }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = colBgSecondary
                g2.fillRoundRect(0, 0, width, height, 12, 12)
                g2.color = colBorder
                g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
            }
        }
        contentPanel.add(tableWrapper)

        // ── Footer ──
        contentPanel.add(spacer(14))
        val footer = JPanel(BorderLayout()).apply {
            alignmentX = LEFT_ALIGNMENT; isOpaque = false
            maximumSize = java.awt.Dimension(Short.MAX_VALUE.toInt(), 22)
        }
        val interactionStr = latest.interactionTypes.filter { it != "unknown" }.sorted().joinToString(", ")
        footer.add(JLabel("Last synced: just now${if (interactionStr.isNotBlank()) " \u00b7 $interactionStr" else ""}").apply {
            foreground = colTextMuted; font = font.deriveFont(11f)
        }, BorderLayout.WEST)
        val refreshBtn = object : JLabel("\u21BB Refresh") {
            init {
                foreground = colTextSecondary; font = font.deriveFont(11f)
                border = BorderFactory.createEmptyBorder(4, 10, 4, 10)
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                isOpaque = false
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        ApplicationManager.getApplication().executeOnPooledThread {
                            val d = loadOverallData()
                            ApplicationManager.getApplication().invokeLater { rebuild(d) }
                        }
                    }
                })
            }
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = colBorder; g2.drawRoundRect(0, 0, width - 1, height - 1, 4, 4)
                super.paintComponent(g)
            }
        }
        footer.add(refreshBtn, BorderLayout.EAST)
        contentPanel.add(footer)
        contentPanel.add(spacer(16))

        contentPanel.revalidate()
        contentPanel.repaint()
        SwingUtilities.invokeLater {
            syncCommitHistoryColumnWidths()
        }
    }
}

// ─── Commit report data shared between classes ──────────────────────────────

private data class CommitReport(
    val commitHash: String,
    val commitMessage: String,
    val branch: String,
    val generatedAt: String,
    val author: String,
    val authorDate: String,
    val totalFilesChanged: Int,
    val totalLinesAdded: Int,
    val totalLinesDeleted: Int,
    val aiLinesAdded: Int,
    val humanLinesAdded: Int,
    val aiPercentage: String,
    val models: List<String>,
    val interactionTypes: List<String>,
    val timeWaitingForAiMs: Long,
    val firstStartCodingTime: String = "",
    val codingTimeMs: Long = 0
)

