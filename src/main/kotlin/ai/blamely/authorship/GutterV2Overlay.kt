// Attribution v2 gutter overlay (docs/attribution-v2-design.md Phase 3) — the
// IntelliJ counterpart to the VS Code GutterV2. Gated by blamely.attributionV2: when
// ON, it paints the active editor's gutter from `blamely authorship <file>` (the
// single per-line source — committed authorship seeded + uncommitted working-log
// edits — the commit note also flips to, invariant I4). When OFF (default), it is
// completely inert and the v1 CliDataService owns the gutter/status bar.
//
// Known limitation while experimental: only the active editor is overlaid (status
// bar / sidebar still aggregate the v1 map), and a v1 refresh may transiently
// repaint between ticks. Repo-wide ownership is a follow-up once validated in the IDE.
package ai.blamely.authorship

import ai.blamely.core.BlameMapService
import ai.blamely.core.LineBlame
import ai.blamely.git.GitUtils
import ai.blamely.settings.BlamelySettings
import ai.blamely.ui.BlameDecorations
import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import java.io.File

class GutterV2Overlay(private val project: Project) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val gson = Gson()

    fun activate() {
        val conn = project.messageBus.connect(this)
        conn.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = schedule()
            },
        )
        // On any blame update (incl. the 3s HEAD poll firing after a commit), RE-FETCH
        // so the gutter reflects the current state — a committed file now has no
        // uncommitted changes and clears, rather than re-asserting stale icons.
        conn.subscribe(
            ai.blamely.core.BlameUpdateListener.TOPIC,
            object : ai.blamely.core.BlameUpdateListener {
                override fun blameUpdated() = schedule()
            },
        )
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val editors = EditorFactory.getInstance().getEditors(event.document)
                    if (editors.any { it.project == project }) schedule()
                }
            },
            this,
        )
        schedule()
    }

    private fun schedule() {
        if (!BlamelySettings.getInstance().attributionV2) return
        alarm.cancelAllRequests()
        alarm.addRequest({ refreshActive() }, 300)
    }

    private fun refreshActive() {
        if (project.isDisposed || !BlamelySettings.getInstance().attributionV2) return
        // Read the selected file on the EDT, then do the CLI call off-EDT.
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeLater
            val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return@invokeLater
            if (!vf.isInLocalFileSystem) return@invokeLater
            val absPath = vf.path
            ApplicationManager.getApplication().executeOnPooledThread { queryAndPaint(absPath) }
        }
    }

    private fun queryAndPaint(absPath: String) {
        if (project.isDisposed) return
        val wl = runAuthorship(absPath) ?: return
        // entries may be EMPTY (e.g. file just committed → no uncommitted changes):
        // set it anyway so the gutter CLEARS rather than keeping stale icons.
        val entries = toLineBlame(wl)
        val service = project.getService(BlameMapService::class.java) ?: return
        service.blameMap.setFileBlame(GitUtils.blameKey(absPath), entries)
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) project.getService(BlameDecorations::class.java)?.refresh()
        }
    }


    private fun runAuthorship(absPath: String): WorkingLogJson? {
        val bin = blamelyBinaryPath()
        if (!File(bin).exists()) return null
        return try {
            val pb = ProcessBuilder(bin, "authorship", absPath)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (proc.waitFor() != 0 || out.isEmpty()) return null
            gson.fromJson(out, WorkingLogJson::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun toLineBlame(wl: WorkingLogJson): List<LineBlame> = workingLogToLineBlame(wl)

    override fun dispose() {}
}
