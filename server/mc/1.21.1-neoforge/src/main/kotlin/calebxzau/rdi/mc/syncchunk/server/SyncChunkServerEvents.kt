package calebxzau.rdi.mc.syncchunk.server

import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.player.PlayerEvent

@EventBusSubscriber(modid = "rdi")
object SyncChunkServerEvents {
    @JvmStatic
    @SubscribeEvent
    fun onPlayerJoin(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        SyncChunkService.sendTo(player).fold(
            onSuccess = {},
            onFailure = { /* The service logs the failure; joining remains available. */ },
        )
    }
}
