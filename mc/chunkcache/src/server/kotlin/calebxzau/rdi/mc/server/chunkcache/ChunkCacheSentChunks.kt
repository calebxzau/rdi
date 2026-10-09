package calebxzau.rdi.mc.server.chunkcache

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.resources.ResourceLocation
import java.util.UUID

/**
 * Main-thread record of chunks each player has already received in this server run.
 *
 * Diagnostics only: it splits no-offer full sends into revisits, which a client cache could have served, and first
 * sends. It outlives cache sessions and logouts because the client cache does; [maxPerPlayer] bounds memory, after
 * which new positions are no longer remembered and later revisits of them count as first sends.
 */
internal class ChunkCacheSentChunks(private val maxPerPlayer: Int = DEFAULT_MAX_PER_PLAYER) {
    private class PlayerChunks {
        val dimensions = HashMap<ResourceLocation, LongOpenHashSet>()
        var size = 0
    }

    private val players = HashMap<UUID, PlayerChunks>()

    /** Records [position] as sent and returns whether it had been sent to [player] in [dimension] before. */
    fun markSent(player: UUID, dimension: ResourceLocation, position: Long): Boolean {
        val chunks = players.getOrPut(player, ::PlayerChunks)
        val existing = chunks.dimensions[dimension]
        if (existing != null && existing.contains(position)) return true
        if (chunks.size >= maxPerPlayer) return false
        (existing ?: LongOpenHashSet().also { chunks.dimensions[dimension] = it }).add(position)
        chunks.size++
        return false
    }

    fun clear() {
        players.clear()
    }

    companion object {
        /** About 4 MiB per player with fastutil's load factor. */
        const val DEFAULT_MAX_PER_PLAYER = 262_144
    }
}
