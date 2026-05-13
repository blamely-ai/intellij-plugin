package ai.blamely.core

import com.intellij.openapi.project.Project

/**
 * Project-level service holding the TraceStore for this project.
 */
class TraceStoreService(val project: Project) {
    val traceStore = TraceStore()
}
