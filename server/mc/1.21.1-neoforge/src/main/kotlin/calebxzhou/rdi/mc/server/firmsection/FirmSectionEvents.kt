package calebxzhou.rdi.mc.server.firmsection

import calebxzau.mc.common2021.sendMessage
import calebxzhou.rdi.mc.firmsection.FirmSectionSetStatus
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.level.BlockEvent

@EventBusSubscriber(modid = "rdi")
object FirmSectionEvents {
    @SubscribeEvent
    @JvmStatic
    fun onBlockPlaced(event: BlockEvent.EntityPlaceEvent) {
        if (event.isCanceled) {
            return
        }
        val player = event.entity as? ServerPlayer ?: return
        val level = event.level as? ServerLevel ?: return
        if (!event.placedBlock.hasBlockEntity() || !FirmSectionService.isAutoSetEnabled(player)) {
            return
        }
        val result = FirmSectionService.set(player, level, event.pos)
        if (result.status == FirmSectionSetStatus.ADDED) {
            player.sendMessage("放置容器的位置已设为持久子区块")
        }
    }
}
