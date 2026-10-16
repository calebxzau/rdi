package calebxzau.rdi.mc.client.chunkcache

import calebxzau.rdi.mc.chunkcache.network.ChunkCacheChannel
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheContextPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheRetirePayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheReusePayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheWarmupPayload
import net.minecraft.client.Minecraft

/**
 * Registers the optional chunk cache channels; server-bound messages are only encoded here. Handlers run on the game
 * thread in order with vanilla packets, so the current connection is the listener a message belongs to.
 */
object ChunkCacheClientNetwork {
    @JvmStatic
    fun register() = ChunkCacheChannel.register { payload, _ ->
        val listener = Minecraft.getInstance().connection ?: return@register
        when (payload) {
            is ChunkCacheContextPayload -> ChunkCacheClient.context(payload, listener)
            is ChunkCacheReusePayload -> ChunkCacheClient.reuse(payload, listener)
            is ChunkCacheRetirePayload -> ChunkCacheClient.retire(payload, listener)
            is ChunkCacheWarmupPayload -> ChunkCacheClient.warmup(payload, listener)
            else -> Unit
        }
    }
}
