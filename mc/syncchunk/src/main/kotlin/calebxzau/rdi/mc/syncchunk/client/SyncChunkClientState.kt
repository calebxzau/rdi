package calebxzau.rdi.mc.syncchunk.client

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import calebxzau.rdi.mc.syncchunk.SyncChunkList
import kotlin.concurrent.Volatile

/**
 * The current room's sync chunks, kept for display only. The list is tagged with the connection it
 * arrived on, so a list from a previous room is never shown in the next one.
 */
object SyncChunkClientState {
    private class Received(val owner: Any, val chunksByDimension: Map<String, List<SyncChunkKey>>)

    @Volatile
    private var received: Received? = null

    /** Outlines of the room's sync chunks are shown. */
    @Volatile
    var showSet = false

    /** The outline of the chunk the player stands in is shown. */
    @Volatile
    var showHere = false

    /** Replaces the whole list; [owner] must be the connection the list arrived on. */
    fun replace(owner: Any, list: SyncChunkList) {
        received = Received(owner, list.chunks.groupBy { it.dimensionId })
    }

    /** Clears the list of [owner], or any list when [owner] is null. */
    fun clear(owner: Any?) {
        val current = received ?: return
        if (owner == null || current.owner === owner) received = null
    }

    /** Chunks of [dimensionId], or null when [owner] has not received a list yet. */
    fun chunksIn(owner: Any, dimensionId: String): List<SyncChunkKey>? =
        received?.takeIf { it.owner === owner }?.let { it.chunksByDimension[dimensionId].orEmpty() }
}
