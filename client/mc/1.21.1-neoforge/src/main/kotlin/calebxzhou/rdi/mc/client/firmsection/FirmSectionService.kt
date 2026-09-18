package calebxzhou.rdi.mc.client.firmsection

import calebxzhou.rdi.mc.client.network.RFirmSectionsPayload
import calebxzhou.rdi.mc.firmsection.FirmSectionKey
import calebxzhou.rdi.mc.firmsection.FirmSectionListResult
import calebxzhou.rdi.mc.firmsection.FirmSectionSetResult
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.client.server.IntegratedServer
import net.minecraft.world.level.ChunkPos
import net.neoforged.neoforge.network.PacketDistributor

object FirmSectionService {
    internal val flow = _root_ide_package_.calebxzhou.rdi.mc.server.firmsection.FirmSectionServiceFlow(
        data = { server ->
            server.overworld().dataStorage.computeIfAbsent(FirmSectionSavedData.factory(), FirmSectionSavedData.FILE_ID)
        },
        saveChunk = { _, _ -> },
        broadcast = ::sendFirmSectionsToAll,
        syncAutoSet = false,
    )

    fun isAutoSetEnabled(player: ServerPlayer): Boolean = flow.isAutoSetEnabled(player)

    fun setAutoSetEnabled(player: ServerPlayer, enabled: Boolean) =
        flow.setAutoSetEnabled(player, enabled)

    fun set(player: ServerPlayer): FirmSectionSetResult = flow.set(player)

    fun set(player: ServerPlayer, level: ServerLevel, pos: BlockPos): FirmSectionSetResult =
        flow.set(player, level, pos)

    fun unset(player: ServerPlayer) = flow.unset(player)

    fun list(player: ServerPlayer): FirmSectionListResult = flow.list(player)

    fun hasFirmChunk(level: ServerLevel, chunkPos: ChunkPos): Boolean =
        flow.hasFirmChunk(level, chunkPos)

    fun all(server: MinecraftServer): List<FirmSectionKey> = flow.all(server)

    fun sendFirmSectionsTo(player: ServerPlayer) {
        if (player.server !is IntegratedServer) {
            return
        }
        PacketDistributor.sendToPlayer(player, payload(player.server))
    }

    private fun sendFirmSectionsToAll(server: MinecraftServer) {
        if (server !is IntegratedServer) {
            return
        }
        val payload = payload(server)
        for (player in server.playerList.players) {
            PacketDistributor.sendToPlayer(player, payload)
        }
    }

    private fun payload(server: MinecraftServer): RFirmSectionsPayload = RFirmSectionsPayload(
        all(server).map { section ->
            RFirmSectionsPayload.Entry(
                section.dimensionId,
                section.chunkX,
                section.sectionY,
                section.chunkZ,
            )
        }
    )
}
