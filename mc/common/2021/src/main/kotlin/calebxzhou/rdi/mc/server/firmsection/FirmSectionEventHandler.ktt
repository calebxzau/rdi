package calebxzhou.rdi.mc.server.firmsection

import calebxzhou.rdi.mc.firmsection.FirmSectionSetStatus
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

class FirmSectionEventHandler(
    private val service: FirmSectionServiceFlow,
) {
    fun handle(
        player: ServerPlayer,
        level: ServerLevel,
        pos: BlockPos,
        hasBlockEntity: Boolean,
        notify: (ServerPlayer) -> Unit,
    ) {
        if (!hasBlockEntity || !service.isAutoSetEnabled(player)) {
            return
        }
        if (service.set(player, level, pos).status == FirmSectionSetStatus.ADDED) {
            notify(player)
        }
    }
}
