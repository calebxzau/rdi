package calebxzau.rdi.mc.syncchunk

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Path
import java.util.UUID

/** NBT body of `rdi_sync_chunks.dat`, identical for every version that reads it. */
object SyncChunkSaveFormat {
    private const val CHUNKS_TAG = "chunks"
    private const val DIMENSION_TAG = "dimension"
    private const val X_TAG = "x"
    private const val Z_TAG = "z"
    private const val OWNER_TAG = "owner"

    fun dataFile(server: MinecraftServer): Path =
        server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("${SyncChunkStorage.FILE_ID}.dat")

    fun write(entries: List<SyncChunkEntry>, tag: CompoundTag): CompoundTag {
        val chunks = ListTag()
        entries.forEach { entry ->
            chunks.add(CompoundTag().apply {
                putString(DIMENSION_TAG, entry.key.dimensionId)
                putInt(X_TAG, entry.key.chunkX)
                putInt(Z_TAG, entry.key.chunkZ)
                putString(OWNER_TAG, entry.ownerId.toString())
            })
        }
        tag.put(CHUNKS_TAG, chunks)
        return tag
    }

    /** Rejects the whole file when any entry is invalid, so a partial list is never saved back. */
    fun read(tag: CompoundTag): SyncChunkState {
        require(tag.contains(CHUNKS_TAG, Tag.TAG_LIST.toInt())) { "同步区块存档缺少chunks列表" }
        val chunks = tag.get(CHUNKS_TAG) as ListTag
        require(chunks.isEmpty() || chunks.elementType == Tag.TAG_COMPOUND) { "同步区块存档chunks列表类型无效" }
        require(chunks.size <= SyncChunkState.DEFAULT_MAX_TOTAL) {
            "同步区块存档超过数量上限：${chunks.size}/${SyncChunkState.DEFAULT_MAX_TOTAL}"
        }
        val entries = chunks.mapIndexed { index, element ->
            val chunk = element as CompoundTag
            val label = "同步区块存档第${index + 1}项"
            require(chunk.contains(DIMENSION_TAG, Tag.TAG_STRING.toInt())) { "${label}缺少dimension" }
            require(chunk.contains(X_TAG, Tag.TAG_INT.toInt()) && chunk.contains(Z_TAG, Tag.TAG_INT.toInt())) {
                "${label}缺少坐标"
            }
            require(chunk.contains(OWNER_TAG, Tag.TAG_STRING.toInt())) { "${label}缺少owner" }
            val dimensionId = chunk.getString(DIMENSION_TAG)
            require(SyncChunkList.isValidDimensionId(dimensionId)) { "${label}维度ID无效：${dimensionId}" }
            val owner = runCatching { UUID.fromString(chunk.getString(OWNER_TAG)) }
                .getOrElse { throw IllegalArgumentException("${label}owner无效", it) }
            SyncChunkEntry(SyncChunkKey(dimensionId, chunk.getInt(X_TAG), chunk.getInt(Z_TAG)), owner)
        }
        return SyncChunkState.restore(entries)
    }
}
