package ai.blamely.core

import com.intellij.openapi.project.Project

/** Project-level in-memory blame view populated from oobeya-cli SQLite. */
class BlameMapService(val project: Project) {
    val blameMap = BlameMap()
}
