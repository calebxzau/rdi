package calebxzau.rdi.mc.client.syncchunk

import calebxzau.rdi.mc.syncchunk.client.SyncChunkClientCommands
import calebxzau.rdi.mc.syncchunk.client.SyncChunkClientState
import calebxzau.rdi.mc.syncchunk.client.SyncChunkOutlines
import calebxzau.rdi.mc.v20.forge.syncchunk.SyncChunkChannel
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.RegisterClientCommandsEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.slf4j.LoggerFactory

/** Forge 1.20.1 wiring for the room's sync chunk list, `/syncchunk show|here` and the outlines. */
@Mod.EventBusSubscriber(modid = "rdi", value = [Dist.CLIENT])
object SyncChunkForgeClient {
    private val logger = LoggerFactory.getLogger(SyncChunkForgeClient::class.java)

    @JvmStatic
    fun register() = SyncChunkChannel.register { list, context ->
        list.onSuccess { received ->
            val connection = context.networkManager
            // Lists still queued from a closed connection must not reach the next room.
            if (connection === Minecraft.getInstance().connection?.connection) {
                SyncChunkClientState.replace(connection, received)
            }
        }.onFailure { exception ->
            logger.warn("Rejected a malformed sync chunk list", exception)
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onLoggingOut(event: ClientPlayerNetworkEvent.LoggingOut) {
        SyncChunkClientState.clear(event.connection)
    }

    @SubscribeEvent
    @JvmStatic
    fun registerCommands(event: RegisterClientCommandsEvent) {
        SyncChunkClientCommands.register(event.dispatcher, SyncChunkChannel::isRemotePresent)
    }

    @SubscribeEvent
    @JvmStatic
    fun onRenderLevelStage(event: RenderLevelStageEvent) {
        if (event.stage !== RenderLevelStageEvent.Stage.AFTER_WEATHER || !SyncChunkOutlines.isEnabled()) return
        // In 1.20.1 the model-view matrix already holds the camera rotation at this stage.
        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushPose()
        modelViewStack.setIdentity()
        RenderSystem.applyModelViewMatrix()
        try {
            SyncChunkOutlines.render(event.poseStack, event.camera.position)
        } finally {
            modelViewStack.popPose()
            RenderSystem.applyModelViewMatrix()
        }
    }
}
