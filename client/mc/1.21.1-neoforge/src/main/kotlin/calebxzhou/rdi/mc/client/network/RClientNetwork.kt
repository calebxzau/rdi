package calebxzhou.rdi.mc.client.network

import calebxzhou.rdi.mc.common.RGlobalPlayerList
import calebxzau.rdi.mc.syncchunk.client.SyncChunkClientState
import calebxzau.rdi.mc.syncchunk.network.RSyncChunksPayload
import com.google.gson.Gson
import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.network.handling.IPayloadHandler
import org.slf4j.LoggerFactory

@EventBusSubscriber(modid = "rdi", value = [Dist.CLIENT])
object RClientNetwork {
    private val GSON = Gson()
    private val logger = LoggerFactory.getLogger(RClientNetwork::class.java)

    @SubscribeEvent
    @JvmStatic
    fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        calebxzau.rdi.mc.zstdcodec.v21.RdiExtensionChannels21.register(event, client = true)
        event.registrar("1")
            .optional()
            .playToClient(
                RGlobalPlayerListPayload.TYPE,
                RGlobalPlayerListPayload.STREAM_CODEC,
                IPayloadHandler { payload: RGlobalPlayerListPayload, _: IPayloadContext ->
                    val playerList = GSON.fromJson(payload.json, RGlobalPlayerList::class.java) ?: return@IPayloadHandler
                    GlobalPlayerListState.update(playerList)
                })
            .playToClient(
                RSyncChunksPayload.TYPE,
                RSyncChunksPayload.STREAM_CODEC
            ) { payload: RSyncChunksPayload, context: IPayloadContext ->
                payload.list.onSuccess { list ->
                    val connection = context.connection()
                    // Lists still queued from a closed connection must not reach the next room.
                    if (connection === Minecraft.getInstance().connection?.connection) {
                        SyncChunkClientState.replace(connection, list)
                    }
                }.onFailure { exception ->
                    logger.warn("Rejected a malformed sync chunk list", exception)
                }
            }
    }

}
