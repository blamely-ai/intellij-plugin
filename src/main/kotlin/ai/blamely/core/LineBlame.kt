package ai.blamely.core

/**
 * Per-line attribution: AI or human.
 * Mirrors the Blamely VS Code extension data model for .git/blamely compatibility.
 */
data class LineBlame(
    var lineNumber: Int,
    var authorType: AuthorType,
    var provider: String? = null,
    var timestamp: String,
    var commitSha: String? = null,
    var model: String? = null,
    var prompt: String? = null,
    var interactionType: String? = null,
    var aiChars: Int = 0,
    var humanChars: Int = 0,
    var changeType: ChangeType = ChangeType.ADD,
    var newLineNumber: Int? = null,
    var oldLineNumber: Int? = null,
    var codingType: CodingType = CodingType.TYPING,
    /** IDE / CLI label (e.g. full product name, `ai_cli`). Written to *.blame.json. */
    var ide: String? = null,
    /**
     * True when this entry came from an explicit AI gen_type (completion/chat/cli)
     * whose recorded line range was TIGHT — i.e. a real editor/hook edit, not a
     * wide whole-file snapshot. Such entries are trusted as-is and exempted from
     * the git-diff constrain. Driven by gen_type + range width, NOT by raw_meta
     * source, so it works identically across IDEs (VS Code, IntelliJ, …).
     */
    var boundedAiRange: Boolean = false,
    /** AI matched by content_sha at current line (survives middle inserts shifting line numbers). */
    var contentShaAttributed: Boolean = false,
) {
    enum class AuthorType { HUMAN, AI }
    enum class ChangeType { ADD, DELETE }
    enum class CodingType { TYPING, BULK_INSERT }

    fun effectiveAuthorType(): AuthorType {
        if (isAiInteractionType(interactionType)) return AuthorType.AI
        val total = aiChars + humanChars
        if (total <= 0) return authorType
        return if (aiChars >= humanChars) AuthorType.AI else AuthorType.HUMAN
    }

    companion object {
        fun isAiInteractionType(interactionType: String?): Boolean =
            when (interactionType?.trim()?.lowercase()) {
                "completion", "chat", "cli" -> true
                else -> false
            }

        fun betterLineEntry(current: LineBlame?, candidate: LineBlame): LineBlame {
            if (current == null) return candidate
            val currentIsAi = current.effectiveAuthorType() == AuthorType.AI
            val candidateIsAi = candidate.effectiveAuthorType() == AuthorType.AI
            if (candidateIsAi != currentIsAi) return if (candidateIsAi) candidate else current

            val currentTotal = current.aiChars + current.humanChars
            val candidateTotal = candidate.aiChars + candidate.humanChars
            return if (candidateTotal >= currentTotal) candidate else current
        }
    }
}
