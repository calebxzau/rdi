package calebxzau.rdi.mc.client.chunkcache

import calebxzau.rdi.mc.chunkcache.network.*
import net.minecraft.client.multiplayer.ClientPacketListener
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext

@EventBusSubscriber(modid = "rdi", value = [Dist.CLIENT])
object ChunkCacheClientNetwork {
    @JvmStatic
    @SubscribeEvent
    fun register(event: RegisterPayloadHandlersEvent) {
        event.registrar("chunk-cache-1").optional()
            .playToClient(ChunkCacheContextPayload.TYPE, ChunkCacheContextPayload.STREAM_CODEC) { payload, context ->
                listener(context)?.let { ChunkCacheClient.context(payload, it) }
            }
            .playToClient(ChunkCacheReusePayload.TYPE, ChunkCacheReusePayload.STREAM_CODEC) { payload, context ->
                listener(context)?.let { ChunkCacheClient.reuse(payload, it) }
            }
            .playToClient(ChunkCacheRetirePayload.TYPE, ChunkCacheRetirePayload.STREAM_CODEC) { payload, context ->
                listener(context)?.let { ChunkCacheClient.retire(payload, it) }
            }
            .playToClient(ChunkCacheWarmupPayload.TYPE, ChunkCacheWarmupPayload.STREAM_CODEC) { payload, context ->
                listener(context)?.let { ChunkCacheClient.warmup(payload, it) }
            }
            .playToServer(ChunkCacheAbsentPayload.TYPE, ChunkCacheAbsentPayload.STREAM_CODEC) { _, _ -> }
            .playToServer(ChunkCacheOfferPayload.TYPE, ChunkCacheOfferPayload.STREAM_CODEC) { _, _ -> }
            .playToServer(ChunkCacheCancelPayload.TYPE, ChunkCacheCancelPayload.STREAM_CODEC) { _, _ -> }
            .playToServer(ChunkCacheResultPayload.TYPE, ChunkCacheResultPayload.STREAM_CODEC) { _, _ -> }
    }

    private fun listener(context: IPayloadContext): ClientPacketListener? = context.listener() as? ClientPacketListener
}
