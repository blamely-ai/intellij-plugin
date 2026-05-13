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
    var codingType: CodingType = CodingType.TYPING
) {
    enum class AuthorType { HUMAN, AI }
    enum class ChangeType { ADD, DELETE }
    enum class CodingType { TYPING, BULK_INSERT }
}
