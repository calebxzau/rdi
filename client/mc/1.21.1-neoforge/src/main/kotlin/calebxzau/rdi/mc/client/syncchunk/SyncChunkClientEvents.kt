package calebxzau.rdi.mc.client.syncchunk

import calebxzau.rdi.mc.syncchunk.client.SyncChunkClientCommands
import calebxzau.rdi.mc.syncchunk.client.SyncChunkClientState
import calebxzau.rdi.mc.syncchunk.client.SyncChunkOutlines
import calebxzau.rdi.mc.syncchunk.network.RSyncChunksPayload
import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent

/** NeoForge 1.21.1 wiring for `/syncchunk show|here`, the room's list lifetime and the outlines. */
@EventBusSubscriber(modid = "rdi", value = [Dist.CLIENT])
object SyncChunkClientEvents {
    @SubscribeEvent
    @JvmStatic
    fun registerCommands(event: RegisterClientCommandsEvent) {
        SyncChunkClientCommands.register(event.dispatcher) {
            Minecraft.getInstance().connection?.hasChannel(RSyncChunksPayload.TYPE) == true
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onLoggingOut(event: ClientPlayerNetworkEvent.LoggingOut) {
        SyncChunkClientState.clear(event.connection)
    }

    @SubscribeEvent
    @JvmStatic
    fun onRenderLevelStage(event: RenderLevelStageEvent) {
        if (event.stage !== RenderLevelStageEvent.Stage.AFTER_WEATHER) return
        // In 1.21.1 the model-view matrix holds the camera rotation and the event's pose stack is identity.
        SyncChunkOutlines.render(event.poseStack, event.camera.position)
    }
}
