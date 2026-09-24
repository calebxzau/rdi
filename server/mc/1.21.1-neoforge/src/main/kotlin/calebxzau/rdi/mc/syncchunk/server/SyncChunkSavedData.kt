package calebxzau.rdi.mc.syncchunk.server

import calebxzau.rdi.mc.syncchunk.SyncChunkAddResult
import calebxzau.rdi.mc.syncchunk.SyncChunkEntry
import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import calebxzau.rdi.mc.syncchunk.SyncChunkRemoveResult
import calebxzau.rdi.mc.syncchunk.SyncChunkState
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

class SyncChunkSavedData private constructor(private val state: SyncChunkState) : SavedData() {
    fun add(key: SyncChunkKey, ownerId: UUID): SyncChunkAddResult {
        val result = state.add(key, ownerId)
        if (result == SyncChunkAddResult.Added) setDirty()
        return result
    }

    fun remove(key: SyncChunkKey, ownerId: UUID): SyncChunkRemoveResult {
        val result = state.remove(key, ownerId)
        if (result == SyncChunkRemoveResult.Removed) setDirty()
        return result
    }

    fun snapshot(): List<SyncChunkEntry> = state.snapshot()

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val chunks = ListTag()
        state.snapshot().forEach { entry ->
            chunks.add(CompoundTag().apply {
                putString("dimension", entry.key.dimensionId)
                putInt("x", entry.key.chunkX)
                putInt("z", entry.key.chunkZ)
                putString("owner", entry.ownerId.toString())
            })
        }
        tag.put("chunks", chunks)
        return tag
    }

    companion object {
        const val FILE_ID = "rdi_sync_chunks"

        fun factory(): Factory<SyncChunkSavedData> = Factory(
            { SyncChunkSavedData(SyncChunkState.empty()) },
            ::load,
        )

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): SyncChunkSavedData {
            require(tag.contains("chunks", Tag.TAG_LIST.toInt())) { "同步区块存档缺少chunks列表" }
            val chunks = tag.get("chunks") as? ListTag ?: error("同步区块存档chunks列表类型无效")
            require(chunks.isEmpty() || chunks.getElementType() == Tag.TAG_COMPOUND) { "同步区块存档chunks列表类型无效" }
            require(chunks.size <= SyncChunkState.DEFAULT_MAX_TOTAL) {
                "同步区块存档超过数量上限：${chunks.size}/${SyncChunkState.DEFAULT_MAX_TOTAL}"
            }
            val entries = chunks.mapIndexed { index, element ->
                val chunk = element as CompoundTag
                require(chunk.contains("dimension", Tag.TAG_STRING.toInt())) { "同步区块存档第${index + 1}项缺少dimension" }
                require(chunk.contains("x", Tag.TAG_INT.toInt()) && chunk.contains("z", Tag.TAG_INT.toInt())) {
                    "同步区块存档第${index + 1}项缺少坐标"
                }
                require(chunk.contains("owner", Tag.TAG_STRING.toInt())) { "同步区块存档第${index + 1}项缺少owner" }
                val owner = runCatching { UUID.fromString(chunk.getString("owner")) }
                    .getOrElse { throw IllegalArgumentException("同步区块存档第${index + 1}项owner无效", it) }
                SyncChunkEntry(
                    SyncChunkKey(chunk.getString("dimension"), chunk.getInt("x"), chunk.getInt("z")),
                    owner,
                )
            }
            return SyncChunkSavedData(SyncChunkState.restore(entries))
        }
    }
}
