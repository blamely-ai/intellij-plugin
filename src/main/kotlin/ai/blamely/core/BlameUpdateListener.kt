package ai.blamely.core

import com.intellij.util.messages.Topic

/**
 * Notified when blame map is updated (including clear on rollback/undo).
 * Tool window and other UI can refresh to show current state.
 */
interface BlameUpdateListener {
    fun blameUpdated()

    companion object {
        @JvmField
        val TOPIC = Topic.create("Blamely.BlameUpdated", BlameUpdateListener::class.java)
    }
}
