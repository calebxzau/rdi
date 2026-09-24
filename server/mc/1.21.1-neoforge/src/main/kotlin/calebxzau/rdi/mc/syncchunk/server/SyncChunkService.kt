package calebxzau.rdi.mc.syncchunk.server

import calebxzau.rdi.mc.syncchunk.SyncChunkAddResult
import calebxzau.rdi.mc.syncchunk.SyncChunkEntry
import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import calebxzau.rdi.mc.syncchunk.SyncChunkRemoveResult
import calebxzau.rdi.mc.syncchunk.network.RSyncChunksPayload
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.storage.DimensionDataStorage
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.neoforge.network.PacketDistributor
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

object SyncChunkService {
    private val logger = LoggerFactory.getLogger(SyncChunkService::class.java)

    fun add(player: ServerPlayer): Result<SyncChunkAddResult> = mutate(
        player,
        "add",
        { key(player.serverLevel(), player.blockPosition()) },
        { data, target -> data.add(target, player.uuid) },
    ) { it == SyncChunkAddResult.Added }

    fun remove(player: ServerPlayer): Result<SyncChunkRemoveResult> = mutate(
        player,
        "remove",
        { key(player.serverLevel(), player.blockPosition()) },
        { data, target -> data.remove(target, player.uuid) },
    ) { it == SyncChunkRemoveResult.Removed }

    fun remove(player: ServerPlayer, key: SyncChunkKey): Result<SyncChunkRemoveResult> = mutate(
        player,
        "remove",
        { key },
        { data, target -> data.remove(target, player.uuid) },
    ) { it == SyncChunkRemoveResult.Removed }

    fun snapshot(server: MinecraftServer): Result<List<SyncChunkEntry>> = runCatching {
        savedData(server).snapshot()
    }.onFailure { exception ->
        logger.error("Failed to read SyncChunk state", exception)
    }

    fun sendTo(player: ServerPlayer): Result<Unit> = runCatching {
        if (player.connection.hasChannel(RSyncChunksPayload.TYPE)) {
            val data = savedData(player.server).snapshot()
            PacketDistributor.sendToPlayer(player, payload(data))
        }
    }.onFailure { exception ->
        logger.error("Failed to send SyncChunk state to player {}", player.uuid, exception)
    }

    private fun <T> mutate(
        player: ServerPlayer,
        operationName: String,
        resolveKey: () -> SyncChunkKey,
        operation: (SyncChunkSavedData, SyncChunkKey) -> T,
        changed: (T) -> Boolean,
    ): Result<T> {
        var key: SyncChunkKey? = null
        return runCatching {
            resolveKey().also { key = it }.let { target ->
                operation(savedData(player.server), target)
            }
        }.onFailure { exception ->
            logger.error("Failed to {} SyncChunk {} for player {}", operationName, key, player.uuid, exception)
        }.onSuccess { result ->
            if (changed(result)) sendToAll(player.server)
        }
    }

    private fun savedData(server: MinecraftServer): SyncChunkSavedData {
        val dataFile = server.getWorldPath(LevelResource.ROOT)
            .resolve("data")
            .resolve("${SyncChunkSavedData.FILE_ID}.dat")
        return getOrCreate(server.overworld().dataStorage, dataFile)
    }

    internal fun getOrCreate(storage: DimensionDataStorage, dataFile: Path): SyncChunkSavedData {
        val factory = SyncChunkSavedData.factory()
        storage.get(factory, SyncChunkSavedData.FILE_ID)?.let { return it }

        check(Files.notExists(dataFile)) {
            "SyncChunk存档存在或无法确认状态，功能不可用以防覆盖"
        }
        return storage.computeIfAbsent(factory, SyncChunkSavedData.FILE_ID)
    }

    private fun key(level: ServerLevel, pos: BlockPos): SyncChunkKey {
        val chunk = ChunkPos(pos)
        return SyncChunkKey(level.dimension().location().toString(), chunk.x, chunk.z)
    }

    private fun payload(entries: List<SyncChunkEntry>): RSyncChunksPayload = RSyncChunksPayload(
        entries.map { RSyncChunksPayload.Entry(it.key.dimensionId, it.key.chunkX, it.key.chunkZ) }
    )

    private fun sendToAll(server: MinecraftServer) {
        val outgoing = runCatching { payload(savedData(server).snapshot()) }.getOrElse { exception ->
            logger.error("Failed to prepare SyncChunk update", exception)
            return
        }

        runCatching {
            server.playerList.players.forEach { player ->
                runCatching {
                    if (player.connection.hasChannel(RSyncChunksPayload.TYPE)) {
                        PacketDistributor.sendToPlayer(player, outgoing)
                    }
                }.onFailure { exception ->
                    logger.error("Failed to send SyncChunk update to player {}", player.uuid, exception)
                }
            }
        }.onFailure { exception ->
            logger.error("Failed to broadcast SyncChunk update", exception)
        }
    }
}
