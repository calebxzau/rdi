package calebxzau.rdi.mc.client.syncchunk

import java.util.Collections
import java.util.UUID
import calebxzau.rdi.mc.syncchunk.SyncChunkKey

data class DmSyncChunkEntry(val key: SyncChunkKey, val ownerId: UUID)

data class DmSyncChunkLimits(val maxTotal: Int) {
    init {
        require(maxTotal > 0)
    }
}

class DmSyncChunkSnapshot(
    val revision: Long,
    chunks: List<DmSyncChunkEntry>,
    val limits: DmSyncChunkLimits,
) {
    val chunks: List<DmSyncChunkEntry> = Collections.unmodifiableList(chunks.toList())

    init {
        require(revision >= 0)
        require(this.chunks.map { it.key }.distinct().size == this.chunks.size)
    }
}

data class DmSyncChunkMutation(
    val snapshot: DmSyncChunkSnapshot,
    val outcome: Outcome,
) {
    enum class Outcome { Added, AlreadyPresent, Removed, AlreadyAbsent }
}

class SyncChunkRequestError(
    val reason: String,
    override val message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

enum class DmSyncChunkOperationKind { Add, Remove }

data class DmSyncChunkOperation(
    val kind: DmSyncChunkOperationKind,
    val playerId: UUID,
    val chunk: SyncChunkKey,
)
