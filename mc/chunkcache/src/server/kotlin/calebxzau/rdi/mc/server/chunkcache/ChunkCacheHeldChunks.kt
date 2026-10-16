package calebxzau.rdi.mc.server.chunkcache

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet
import it.unimi.dsi.fastutil.longs.LongSet

/**
 * Chunks held back during warm-up on a server that sends each chunk at once (1.20.1), which has no pending-send queue
 * to leave them in. Holds packed chunk positions in the order vanilla would have sent them. Owned by the server thread.
 */
class ChunkCacheHeldChunks {
    private val positions = LongLinkedOpenHashSet()

    val size: Int get() = positions.size

    fun isEmpty(): Boolean = positions.isEmpty()

    fun hold(position: Long) {
        positions.add(position)
    }

    /** The chunk left the player's view; true when it was held, so vanilla's unload of a chunk never sent is skipped. */
    fun forget(position: Long): Boolean = positions.remove(position)

    /**
     * Drops positions vanilla would no longer send now: outside the view, or no longer ready. A chunk that becomes ready
     * again is sent through the normal path, which holds it again if needed. Returns the remaining positions.
     */
    fun prune(sendable: (Long) -> Boolean): LongSet {
        val iterator = positions.iterator()
        while (iterator.hasNext()) {
            if (!sendable(iterator.nextLong())) iterator.remove()
        }
        return positions
    }

    fun release(position: Long): Boolean = positions.remove(position)

    /** Every held position, in hold order, leaving the queue empty. */
    fun takeAll(): LongArray = positions.toLongArray().also { positions.clear() }
}
