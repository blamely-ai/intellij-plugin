package ai.blamely.actions

import ai.blamely.core.BlameMapService
import ai.blamely.core.TraceStoreService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * Shows approximate in-memory usage of Blamely (BlameMap + TraceStore) and how to check full memory.
 */
class ShowMemoryDiagnosticsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val blameService = project.getService(BlameMapService::class.java)
        val traceService = project.getService(TraceStoreService::class.java)

        val lines = mutableListOf<String>()
        var trackedFiles = 0
        var totalBlameEntries = 0
        var suggestionCount = 0

        if (blameService != null) {
            val files = blameService.blameMap.getTrackedFiles()
            trackedFiles = files.size
            totalBlameEntries = files.sumOf { blameService.blameMap.getBlame(it).size }
            lines.add("BlameMap: $trackedFiles file(s), $totalBlameEntries line entries")
        } else {
            lines.add("BlameMap: not loaded")
        }

        if (traceService != null) {
            val all = traceService.traceStore.getAllSuggestions()
            suggestionCount = all.size
            lines.add("TraceStore: $suggestionCount suggestion(s)")
        } else {
            lines.add("TraceStore: not loaded")
        }

        // Rough estimate: LineBlame ~200–400 bytes each, SuggestionRecord ~500+ bytes, map overhead
        val estimatedBytes = totalBlameEntries * 300L + suggestionCount * 600L + trackedFiles * 100L
        val estimatedKb = estimatedBytes / 1024
        lines.add("Rough in-memory estimate: ~$estimatedKb KB (plugin data only)")
        lines.add("")
        lines.add("To see full IDE memory: Help → Diagnostic Tools → Capture Memory Snapshot. In the snapshot, filter by \"blamely\" or \"ai.blamely\" to see plugin objects.")

        val message = lines.joinToString("\n")
        notify(project, "Blamely memory (current project)", message)
    }

    private fun notify(project: Project, title: String, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Blamely")
            .createNotification(title, message, NotificationType.INFORMATION)
            .notify(project)
    }
}
