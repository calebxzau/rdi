package calebxzau.rdi.mc.client.chunkcache

import calebxzau.rdi.mc.chunkcache.network.ChunkCacheChannel
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheContextPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheRetirePayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheReusePayload

/** Registers the optional chunk cache channel; server-bound messages are only encoded here. */
object ChunkCacheClientNetwork {
    @JvmStatic
    fun register() = ChunkCacheChannel.register { payload, _ ->
        when (payload) {
            is ChunkCacheContextPayload -> ChunkCacheClient.context(payload)
            is ChunkCacheReusePayload -> ChunkCacheClient.reuse(payload)
            is ChunkCacheRetirePayload -> ChunkCacheClient.retire(payload)
            else -> Unit
        }
    }
}
