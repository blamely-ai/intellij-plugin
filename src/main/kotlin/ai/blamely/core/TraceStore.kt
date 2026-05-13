package ai.blamely.core

import java.util.UUID

/**
 * In-memory store of suggestion records (pending/accepted).
 * Mirrors Blamely VS Code TraceStore for session persistence.
 */
data class SuggestionRecord(
    val suggestionId: String,
    val timestamp: String,
    val filePath: String,
    val lineStart: Int,
    val lineEnd: Int,
    val suggestedText: String,
    val providerId: String,
    var accepted: Boolean = false,
    var acceptedAt: String? = null,
    var finalText: String? = null,
    val modelName: String? = null,
    val prompt: String? = null
)

class TraceStore {
    private val suggestions = mutableListOf<SuggestionRecord>()
    private val pending = mutableListOf<SuggestionRecord>()

    fun addSuggestion(
        filePath: String,
        lineStart: Int,
        lineEnd: Int,
        suggestedText: String,
        providerId: String,
        modelName: String? = null,
        prompt: String? = null
    ): SuggestionRecord {
        val r = SuggestionRecord(
            suggestionId = UUID.randomUUID().toString(),
            timestamp = java.time.Instant.now().toString(),
            filePath = filePath,
            lineStart = lineStart,
            lineEnd = lineEnd,
            suggestedText = suggestedText,
            providerId = providerId,
            modelName = modelName,
            prompt = prompt
        )
        suggestions.add(r)
        pending.add(r)
        return r
    }

    fun markAccepted(suggestionId: String, finalText: String) {
        suggestions.find { it.suggestionId == suggestionId }?.let {
            it.accepted = true
            it.acceptedAt = java.time.Instant.now().toString()
            it.finalText = finalText
        }
        pending.removeAll { it.suggestionId == suggestionId }
    }

    fun markRejected(suggestionId: String) {
        pending.removeAll { it.suggestionId == suggestionId }
    }

    fun expirePending(timeoutMs: Long) {
        val now = System.currentTimeMillis()
        pending.removeAll { r ->
            val age = now - java.time.Instant.parse(r.timestamp).toEpochMilli()
            if (age > timeoutMs) {
                true
            } else false
        }
    }

    fun getPendingSuggestions(): List<SuggestionRecord> = pending.toList()
    fun getAllSuggestions(): List<SuggestionRecord> = suggestions.toList()
    fun getAcceptedSuggestions(): List<SuggestionRecord> = suggestions.filter { it.accepted }
}
