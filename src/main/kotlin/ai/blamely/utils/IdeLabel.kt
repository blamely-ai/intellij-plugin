package ai.blamely.utils

import com.intellij.openapi.application.ApplicationNamesInfo

/** Product name for blame snapshots / reports parity with VS Code `env.appName`. */
object IdeLabel {
    fun current(): String {
        val n = ApplicationNamesInfo.getInstance().fullProductName?.trim().orEmpty()
        return if (n.isNotEmpty()) n else "IntelliJ"
    }
}
