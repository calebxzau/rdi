package calebxzau.rdi.common.model

import kotlinx.serialization.Serializable

@Serializable
enum class HostRollbackPointKind {
    /** Taken every 5 minutes while the host runs and something changed. */
    Auto,

    /** Taken right before a rollback was applied, so the rollback itself can be undone. */
    BeforeRollback,
}

/** One 回档点 of a host's sync chunks and player data. */
@Serializable
data class HostRollbackPointVo(
    val id: String,
    val kind: HostRollbackPointKind,
    val createdAt: Long,
    val chunkCount: Int,
    val playerCount: Int,
    /** Sync chunks whose blocks changed since the previous point. */
    val changedChunks: Int,
    val changedPlayers: Int,
)

@Serializable
enum class HostRollbackRequestState {
    /** Waiting for the host's next start. */
    Requested,

    /** The host started writing it; the next start continues. */
    Applying,
}

/** A rollback that applies when the host starts next. */
@Serializable
data class HostRollbackPendingVo(
    val pointId: String,
    val requestedAt: Long,
    val state: HostRollbackRequestState,
    /** The owner gave up a rollback that had started writing; the next start restores the state before it. */
    val abandoned: Boolean,
)

@Serializable
enum class HostRollbackOutcome {
    Done,
    Failed,
}

/** What the host reported for the last rollback it handled. */
@Serializable
data class HostRollbackResultVo(
    /** Null when the request itself could not be read. */
    val pointId: String?,
    val beforePointId: String?,
    val outcome: HostRollbackOutcome,
    val message: String?,
    val finishedAt: Long,
)

@Serializable
data class HostRollbackStatusVo(
    /** Newest first. */
    val points: List<HostRollbackPointVo>,
    val pending: HostRollbackPendingVo?,
    val lastResult: HostRollbackResultVo?,
    val usedBytes: Long,
    val maxBytes: Long,
)

@Serializable
data class HostRollbackCreateDto(val pointId: String)

@Serializable
data class HostRollbackCreateVo(
    /** True when the host was running and has been stopped; the rollback applies when it is started again. */
    val stoppedHost: Boolean,
)
