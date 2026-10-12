package calebxzau.rdi.mc.syncchunk.backup

enum class SyncChunkRollbackState {
    /** Written by the master; the host has not touched the world yet. */
    Requested,

    /** The before-rollback point exists and writing has started; a later start writes everything again. */
    Applying,
}

/** Content of `rollback-request.json`: the master asks the host to restore [pointId] on its next start. */
data class SyncChunkRollbackRequest(
    /** UUIDv7. */
    val id: String,
    val pointId: String,
    /** Token of the world the point belongs to; a replaced world never receives the rollback. */
    val worldToken: String,
    /** Account id (hex) of the owner who asked for it. */
    val requestedBy: String,
    val requestedAt: Long,
    val state: SyncChunkRollbackState = SyncChunkRollbackState.Requested,
    /** Set once the host took the before-rollback point. */
    val beforePointId: String? = null,
    /** The owner gave up while [SyncChunkRollbackState.Applying]; [pointId] then is the before-rollback point. */
    val abandoned: Boolean = false,
) {
    init {
        require(state != SyncChunkRollbackState.Applying || beforePointId != null) { "回档进行中但缺少回档前的回档点" }
    }

    /** Points retention must keep until this request is finished. */
    fun pinnedPointIds(): Set<String> = setOfNotNull(pointId, beforePointId)

    /** Restores the state before this rollback instead; only meaningful while [SyncChunkRollbackState.Applying]. */
    fun abandon(): SyncChunkRollbackRequest {
        val before = checkNotNull(beforePointId) { "回档尚未开始写入" }
        return copy(pointId = before, abandoned = true)
    }
}

enum class SyncChunkRollbackOutcome {
    Done,
    Failed,
}

/** Content of `rollback-result.json`, written by the host for the last request it finished. */
data class SyncChunkRollbackResult(
    /** Null when the request could not be read. */
    val requestId: String?,
    val pointId: String?,
    val beforePointId: String?,
    val outcome: SyncChunkRollbackOutcome,
    /** Why it failed, for the owner. */
    val message: String?,
    val finishedAt: Long,
)
