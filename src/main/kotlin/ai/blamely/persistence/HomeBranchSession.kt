package ai.blamely.persistence

import com.google.gson.annotations.SerializedName

/** Persisted under `~/.blamely/session/{repoKey}/{branchKey}/open|closed/`. */
data class HomeBranchSession(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("repo_root") val repoRoot: String,
    val branch: String,
    var status: String = STATUS_OPEN,
    @SerializedName("opened_at") val openedAt: String,
    @SerializedName("updated_at") var updatedAt: String,
    @SerializedName("closed_at") var closedAt: String? = null,
    @SerializedName("commit_sha") var commitSha: String? = null,
    @SerializedName("commit_note_attached") var commitNoteAttached: Boolean = false,
    @SerializedName("stash_links") var stashLinks: MutableList<StashLinkEntry> = mutableListOf()
) {
    companion object {
        const val STATUS_OPEN = "open"
        const val STATUS_CLOSED = "closed"
        const val STATUS_STASHED = "stashed"
    }
}

data class StashLinkEntry(
    @SerializedName("stash_ref") val stashRef: String,
    @SerializedName("stash_sha") val stashSha: String,
    val message: String? = null,
    @SerializedName("linked_at") val linkedAt: String,
    @SerializedName("note_attached") var noteAttached: Boolean = false
)
