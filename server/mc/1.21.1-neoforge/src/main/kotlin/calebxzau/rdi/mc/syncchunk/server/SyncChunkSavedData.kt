package calebxzau.rdi.mc.syncchunk.server

import calebxzau.rdi.mc.syncchunk.SyncChunkAddResult
import calebxzau.rdi.mc.syncchunk.SyncChunkEntry
import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import calebxzau.rdi.mc.syncchunk.SyncChunkRemoveResult
import calebxzau.rdi.mc.syncchunk.SyncChunkSaveFormat
import calebxzau.rdi.mc.syncchunk.SyncChunkState
import calebxzau.rdi.mc.syncchunk.SyncChunkStore
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

class SyncChunkSavedData private constructor(private val state: SyncChunkState) : SavedData(), SyncChunkStore {
    override fun add(key: SyncChunkKey, ownerId: UUID): SyncChunkAddResult {
        val result = state.add(key, ownerId)
        if (result == SyncChunkAddResult.Added) setDirty()
        return result
    }

    override fun remove(key: SyncChunkKey, ownerId: UUID): SyncChunkRemoveResult {
        val result = state.remove(key, ownerId)
        if (result == SyncChunkRemoveResult.Removed) setDirty()
        return result
    }

    override fun snapshot(): List<SyncChunkEntry> = state.snapshot()

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag =
        SyncChunkSaveFormat.write(state.snapshot(), tag)

    companion object {
        fun factory(): Factory<SyncChunkSavedData> = Factory(
            { SyncChunkSavedData(SyncChunkState.empty()) },
            { tag, _ -> SyncChunkSavedData(SyncChunkSaveFormat.read(tag)) },
        )
    }
}
