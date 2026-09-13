package calebxzhou.rdi.mc.server.firmsection

import calebxzhou.rdi.mc.firmsection.FirmSectionKey
import calebxzhou.rdi.mc.firmsection.FirmSectionListResult
import calebxzhou.rdi.mc.firmsection.FirmSectionSetResult
import calebxzhou.rdi.mc.firmsection.FirmSectionSetStatus
import calebxzau.rdi.mc.server.mixin.AChunkMap
import calebxzhou.rdi.mc.server.network.RServerNetwork
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos

object FirmSectionService {

    fun isAutoSetEnabled(player: ServerPlayer): Boolean = data(player.server).isAutoSetEnabled(player.uuid)

    fun setAutoSetEnabled(player: ServerPlayer, enabled: Boolean) {
        data(player.server).setAutoSetEnabled(player.uuid, enabled)
    }

    fun set(player: ServerPlayer): FirmSectionSetResult {
        val key = target(player)
        return set(player, player.serverLevel(), player.blockPosition(), key)
    }

    fun set(player: ServerPlayer, level: ServerLevel, pos: BlockPos): FirmSectionSetResult {
        val key = target(level, pos)
        return set(player, level, pos, key)
    }

    private fun set(
        player: ServerPlayer,
        level: ServerLevel,
        pos: BlockPos,
        key: FirmSectionKey
    ): FirmSectionSetResult {
        val data = data(player.server)
        val result = data.set(player.uuid, key)
        if (result.status == FirmSectionSetStatus.ADDED) {
            saveFirmChunkNow(level, pos)
            saveFirmSectionDataNow(player.server)
            RServerNetwork.sendFirmSectionsToAll(player.server)
        }
        return result
    }

    fun unset(player: ServerPlayer) =
        data(player.server).unset(player.uuid, target(player)).also {
            if (it.removed) {
                RServerNetwork.sendFirmSectionsToAll(player.server)
            }
        }

    fun list(player: ServerPlayer): FirmSectionListResult {
        return data(player.server).list(player.uuid)
    }

    fun hasFirmChunk(level: ServerLevel, chunkPos: ChunkPos): Boolean =
        data(level.server).hasFirmChunk(
            dimensionId = level.dimension().location().toString(),
            chunkX = chunkPos.x,
            chunkZ = chunkPos.z,
        )

    fun all(server: MinecraftServer): List<FirmSectionKey> = data(server).allSections()

    private fun data(server: MinecraftServer): FirmSectionSavedData =
        server.overworld().dataStorage.computeIfAbsent(FirmSectionSavedData.factory(), FirmSectionSavedData.FILE_ID)

    private fun target(player: ServerPlayer): FirmSectionKey {
        val pos = player.blockPosition()
        return target(player.serverLevel(), pos)
    }

    private fun target(level: ServerLevel, pos: BlockPos): FirmSectionKey {
        return FirmSectionKey(
            dimensionId = level.dimension().location().toString(),
            chunkX = SectionPos.blockToSectionCoord(pos.x),
            sectionY = SectionPos.blockToSectionCoord(pos.y),
            chunkZ = SectionPos.blockToSectionCoord(pos.z),
        )
    }

    private fun saveFirmChunkNow(level: ServerLevel, pos: BlockPos) {
        val chunk = level.getChunkAt(pos)
        chunk.isUnsaved = true
        (level.chunkSource.chunkMap as AChunkMap).`rdi$saveChunk`(chunk)
    }

    private fun saveFirmSectionDataNow(server: MinecraftServer) {
        server.overworld().dataStorage.save()
    }

}
