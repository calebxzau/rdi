package calebxzau.rdi.mc.client.chunkcache

import calebxzau.rdi.mc.chunkcache.network.*
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

@EventBusSubscriber(modid = "rdi", value = [Dist.CLIENT])
object ChunkCacheClientNetwork {
    @JvmStatic
    @SubscribeEvent
    fun register(event: RegisterPayloadHandlersEvent) {
        event.registrar("chunk-cache-1").optional()
            .playToClient(ChunkCacheContextPayload.TYPE, ChunkCacheContextPayload.STREAM_CODEC, ChunkCacheClient::context)
            .playToClient(ChunkCacheReusePayload.TYPE, ChunkCacheReusePayload.STREAM_CODEC, ChunkCacheClient::reuse)
            .playToClient(ChunkCacheRetirePayload.TYPE, ChunkCacheRetirePayload.STREAM_CODEC, ChunkCacheClient::retire)
            .playToServer(ChunkCacheOfferPayload.TYPE, ChunkCacheOfferPayload.STREAM_CODEC) { _, _ -> }
            .playToServer(ChunkCacheCancelPayload.TYPE, ChunkCacheCancelPayload.STREAM_CODEC) { _, _ -> }
            .playToServer(ChunkCacheResultPayload.TYPE, ChunkCacheResultPayload.STREAM_CODEC) { _, _ -> }
    }
}
