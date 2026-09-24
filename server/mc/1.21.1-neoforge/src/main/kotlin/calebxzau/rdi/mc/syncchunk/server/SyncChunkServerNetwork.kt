package calebxzau.rdi.mc.syncchunk.server

import calebxzau.rdi.mc.syncchunk.network.RSyncChunksPayload
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.network.handling.IPayloadHandler

@EventBusSubscriber(modid = "rdi")
object SyncChunkServerNetwork {
    @JvmStatic
    @SubscribeEvent
    fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        event.registrar("1")
            .optional()
            .playToClient(
                RSyncChunksPayload.TYPE,
                RSyncChunksPayload.STREAM_CODEC,
                IPayloadHandler { _: RSyncChunksPayload, _: IPayloadContext -> },
            )
    }
}
