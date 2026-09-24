package calebxzau.rdi.mc.client.syncchunk

import net.minecraft.client.server.IntegratedServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.player.PlayerEvent

@EventBusSubscriber(modid = "rdi", value = [Dist.CLIENT])
object SyncChunkEvents {
    @SubscribeEvent
    @JvmStatic
    fun onPlayerJoin(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val server = player.server as? IntegratedServer ?: return
        if (SyncChunkService.hasSnapshot(server)) {
            SyncChunkService.sendSyncChunksTo(player)
        }
    }
}
