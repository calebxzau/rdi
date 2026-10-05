package calebxzau.rdi.mc.syncchunk.server

import calebxzau.rdi.mc.syncchunk.SyncChunkList
import calebxzau.rdi.mc.syncchunk.SyncChunkSaveFormat
import calebxzau.rdi.mc.syncchunk.SyncChunkStorage
import calebxzau.rdi.mc.syncchunk.network.RSyncChunksPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.DimensionDataStorage
import net.neoforged.neoforge.network.PacketDistributor
import org.slf4j.LoggerFactory
import java.nio.file.Path

/** Finds the server's sync chunk data and sends the whole list to players whose client supports it. */
object SyncChunkService {
    private val logger = LoggerFactory.getLogger(SyncChunkService::class.java)

    /** Must run on the server thread. */
    fun store(server: MinecraftServer): Result<SyncChunkSavedData> = runCatching {
        getOrCreate(server.overworld().dataStorage, SyncChunkSaveFormat.dataFile(server))
    }.onFailure { exception ->
        logger.error("Failed to load SyncChunk data", exception)
    }

    fun sendTo(player: ServerPlayer) {
        if (!player.connection.hasChannel(RSyncChunksPayload.TYPE)) return
        list(player.server)?.let { send(player, it) }
    }

    /** Runs on the server thread; a failed send to one player does not stop the others. */
    fun broadcast(server: MinecraftServer) {
        val list = list(server) ?: return
        server.playerList.players.forEach { player ->
            if (player.connection.hasChannel(RSyncChunksPayload.TYPE)) send(player, list)
        }
    }

    internal fun getOrCreate(storage: DimensionDataStorage, dataFile: Path): SyncChunkSavedData {
        val factory = SyncChunkSavedData.factory()
        return SyncChunkStorage.loadOrCreate(
            dataFile,
            { storage.get(factory, SyncChunkStorage.FILE_ID) },
            { storage.computeIfAbsent(factory, SyncChunkStorage.FILE_ID) },
        )
    }

    private fun list(server: MinecraftServer): SyncChunkList? =
        store(server).mapCatching { SyncChunkList.of(it.snapshot()) }.getOrElse { exception ->
            logger.error("Failed to prepare the sync chunk list", exception)
            null
        }

    private fun send(player: ServerPlayer, list: SyncChunkList) {
        try {
            PacketDistributor.sendToPlayer(player, RSyncChunksPayload.of(list))
        } catch (exception: RuntimeException) {
            logger.error("Failed to send the sync chunk list to player {}", player.uuid, exception)
        }
    }
}
