package calebxzau.rdi.mc.client.chunkcache

import calebxzau.rdi.mc.chunkcache.network.*
import calebxzau.rdi.mc.server.chunkcache.ChunkCacheServerService
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.server.level.ServerPlayer
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
            // Guests of this game's integrated server (DM, LAN) send these to the host.
            .playToServer(ChunkCacheAbsentPayload.TYPE, ChunkCacheAbsentPayload.STREAM_CODEC) { payload, context ->
                onServerMain(context) { player -> ChunkCacheServerService.absent(player, payload) }
            }
            .playToServer(ChunkCacheOfferPayload.TYPE, ChunkCacheOfferPayload.STREAM_CODEC) { payload, context ->
                onServerMain(context) { player -> ChunkCacheServerService.offer(player, payload) }
            }
            .playToServer(ChunkCacheCancelPayload.TYPE, ChunkCacheCancelPayload.STREAM_CODEC) { payload, context ->
                onServerMain(context) { player -> ChunkCacheServerService.cancel(player, payload) }
            }
            .playToServer(ChunkCacheResultPayload.TYPE, ChunkCacheResultPayload.STREAM_CODEC) { payload, context ->
                onServerMain(context) { player -> ChunkCacheServerService.result(player, payload) }
            }
            .playToServer(ChunkCachePausedPayload.TYPE, ChunkCachePausedPayload.STREAM_CODEC) { payload, context ->
                onServerMain(context) { player -> ChunkCacheServerService.paused(player, payload) }
            }
    }

    private fun listener(context: IPayloadContext): ClientPacketListener? = context.listener() as? ClientPacketListener

    private fun onServerMain(context: IPayloadContext, action: (ServerPlayer) -> Unit) {
        context.enqueueWork {
            (context.player() as? ServerPlayer)?.let(action)
        }
    }
}
