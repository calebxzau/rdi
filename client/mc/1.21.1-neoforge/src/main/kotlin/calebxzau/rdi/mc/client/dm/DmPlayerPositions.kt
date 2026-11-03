package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Last known chunk of every player with data in the hosted world, for the checkpoint keep set. */
class DmPlayerPositions {
    private val chunks = ConcurrentHashMap<UUID, SyncChunkKey>()

    /** Reads the saved position of every offline player; unreadable player files are logged and skipped. */
    fun load(worldRoot: Path): Result<Int> = runCatching {
        val directory = worldRoot.resolve("playerdata")
        if (!Files.isDirectory(directory)) return@runCatching 0
        var loaded = 0
        Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".dat") }.forEach { file ->
                val id = runCatching { UUID.fromString(file.fileName.toString().removeSuffix(".dat")) }.getOrNull() ?: return@forEach
                runCatching { NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()) }
                    .onSuccess { tag -> chunkOf(tag)?.let { chunks.putIfAbsent(id, it); loaded++ } }
                    .onFailure { error -> logger.warn("Failed to read the position of DM player {}", id, error) }
            }
        }
        loaded
    }

    fun update(player: ServerPlayer) {
        chunks[player.uuid] = SyncChunkKey(player.level().dimension().location().toString(), player.chunkPosition().x, player.chunkPosition().z)
    }

    fun all(): Collection<SyncChunkKey> = chunks.values.toList()

    companion object {
        private val logger = LoggerFactory.getLogger(DmPlayerPositions::class.java)
        private const val OVERWORLD = "minecraft:overworld"

        /** The chunk a saved player stands in, or null when the data has no position. */
        fun chunkOf(tag: CompoundTag): SyncChunkKey? {
            val position = tag.getList("Pos", Tag.TAG_DOUBLE.toInt())
            if (position.size != 3) return null
            val x = position.getDouble(0)
            val z = position.getDouble(2)
            if (!x.isFinite() || !z.isFinite()) return null
            val dimension = tag.getString("Dimension").ifBlank { OVERWORLD }
            return SyncChunkKey(dimension, Math.floor(x).toInt() shr 4, Math.floor(z).toInt() shr 4)
        }
    }
}
