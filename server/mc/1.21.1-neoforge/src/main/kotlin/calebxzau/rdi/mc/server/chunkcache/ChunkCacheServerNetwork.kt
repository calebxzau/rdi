package calebxzau.rdi.mc.server.chunkcache

import calebxzau.rdi.mc.chunkcache.network.ChunkCacheCancelPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheContextPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheOfferPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheResultPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheRetirePayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheReusePayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.network.handling.IPayloadHandler

@EventBusSubscriber(modid = "rdi")
object ChunkCacheServerNetwork {
    @JvmStatic
    @SubscribeEvent
    fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        event.registrar("chunk-cache-1")
            .optional()
            .playToClient(ChunkCacheContextPayload.TYPE, ChunkCacheContextPayload.STREAM_CODEC, noop())
            .playToClient(ChunkCacheRetirePayload.TYPE, ChunkCacheRetirePayload.STREAM_CODEC, noop())
            .playToClient(ChunkCacheReusePayload.TYPE, ChunkCacheReusePayload.STREAM_CODEC, noop())
            .playToServer(ChunkCacheOfferPayload.TYPE, ChunkCacheOfferPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
                onMain(context) { player -> ChunkCacheServerService.offer(player, payload) }
            })
            .playToServer(ChunkCacheCancelPayload.TYPE, ChunkCacheCancelPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
                onMain(context) { player -> ChunkCacheServerService.cancel(player, payload) }
            })
            .playToServer(ChunkCacheResultPayload.TYPE, ChunkCacheResultPayload.STREAM_CODEC, IPayloadHandler { payload, context ->
                onMain(context) { player -> ChunkCacheServerService.result(player, payload) }
            })
    }

    private fun <T : CustomPacketPayload> noop(): IPayloadHandler<T> = IPayloadHandler { _: T, _: IPayloadContext -> }

    private fun onMain(context: IPayloadContext, action: (ServerPlayer) -> Unit) {
        context.enqueueWork {
            (context.player() as? ServerPlayer)?.let(action)
        }
    }
}
