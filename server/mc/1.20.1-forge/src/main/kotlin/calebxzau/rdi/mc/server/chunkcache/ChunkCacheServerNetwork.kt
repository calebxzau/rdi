package calebxzau.rdi.mc.server.chunkcache

import calebxzau.rdi.mc.chunkcache.network.ChunkCacheCancelPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheChannel
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheOfferPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheResultPayload

/** Registers the optional chunk cache channel; client-bound messages are ignored here. */
object ChunkCacheServerNetwork {
    @JvmStatic
    fun register() = ChunkCacheChannel.register { payload, context ->
        val player = context.sender ?: return@register
        when (payload) {
            is ChunkCacheOfferPayload -> ChunkCacheServerService.offer(player, payload)
            is ChunkCacheCancelPayload -> ChunkCacheServerService.cancel(player, payload)
            is ChunkCacheResultPayload -> ChunkCacheServerService.result(player, payload)
            else -> Unit
        }
    }
}
