package calebxzhou.rdi.mc.server.firmsection

import calebxzhou.rdi.mc.firmsection.FirmSectionKey
import calebxzhou.rdi.mc.firmsection.FirmSectionListResult
import calebxzhou.rdi.mc.firmsection.FirmSectionSetResult
import calebxzhou.rdi.mc.firmsection.FirmSectionSetStatus
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos

class FirmSectionServiceFlow(
    private val data: (MinecraftServer) -> calebxzhou.rdi.mc.client.firmsection.FirmSectionSavedData,
    private val saveChunk: (ServerLevel, BlockPos) -> Unit,
    private val broadcast: (MinecraftServer) -> Unit,
    private val syncAutoSet: Boolean,
) {
    fun isAutoSetEnabled(player: ServerPlayer): Boolean =
        data(player.server).isAutoSetEnabled(player.uuid)

    fun setAutoSetEnabled(player: ServerPlayer, enabled: Boolean) {
        data(player.server).setAutoSetEnabled(player.uuid, enabled)
        if (syncAutoSet) {
            broadcast(player.server)
        }
    }

    fun set(player: ServerPlayer): FirmSectionSetResult =
        set(player, player.serverLevel(), player.blockPosition())

    fun set(player: ServerPlayer, level: ServerLevel, pos: BlockPos): FirmSectionSetResult {
        val result = data(player.server).set(player.uuid, target(level, pos))
        if (result.status == FirmSectionSetStatus.ADDED) {
            saveChunk(level, pos)
            saveFirmSectionDataNow(player.server)
            broadcast(player.server)
        }
        return result
    }

    fun unset(player: ServerPlayer) =
        data(player.server).unset(player.uuid, target(player.serverLevel(), player.blockPosition())).also {
            if (it.removed) {
                broadcast(player.server)
            }
        }

    fun list(player: ServerPlayer): FirmSectionListResult =
        data(player.server).list(player.uuid)

    fun hasFirmChunk(level: ServerLevel, chunkPos: ChunkPos): Boolean =
        data(level.server).hasFirmChunk(
            level.dimension().location().toString(),
            chunkPos.x,
            chunkPos.z
        )

    fun all(server: MinecraftServer): List<FirmSectionKey> = data(server).allSections()

    private fun target(level: ServerLevel, pos: BlockPos) = FirmSectionKey(
        level.dimension().location().toString(),
        SectionPos.blockToSectionCoord(pos.x),
        SectionPos.blockToSectionCoord(pos.y),
        SectionPos.blockToSectionCoord(pos.z)
    )

    private fun saveFirmSectionDataNow(server: MinecraftServer) {
        server.overworld().dataStorage.save()
    }
}
