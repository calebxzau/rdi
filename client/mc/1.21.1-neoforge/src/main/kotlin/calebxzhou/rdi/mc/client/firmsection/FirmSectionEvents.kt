package calebxzhou.rdi.mc.client.firmsection

import calebxzau.mc.common2021.sendMessage
import net.minecraft.client.server.IntegratedServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.level.BlockEvent

@EventBusSubscriber(modid = "rdi", value = [Dist.CLIENT])
object FirmSectionEvents {
    @SubscribeEvent
    @JvmStatic
    fun onBlockPlaced(event: BlockEvent.EntityPlaceEvent) {
        if (event.isCanceled) {
            return
        }
        val player = event.entity as? ServerPlayer ?: return
        val level = event.level as? ServerLevel ?: return
        if (level.server !is IntegratedServer) {
            return
        }
        _root_ide_package_.calebxzhou.rdi.mc.server.firmsection.FirmSectionEventHandler(FirmSectionService.flow).handle(
            player = player,
            level = level,
            pos = event.pos,
            hasBlockEntity = event.placedBlock.hasBlockEntity(),
            notify = { it.sendMessage("放置容器的位置，已设为同步区域") },
        )
    }

    @SubscribeEvent
    @JvmStatic
    fun onPlayerJoin(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (player.server is IntegratedServer) {
            FirmSectionService.sendFirmSectionsTo(player)
        }
    }
}
