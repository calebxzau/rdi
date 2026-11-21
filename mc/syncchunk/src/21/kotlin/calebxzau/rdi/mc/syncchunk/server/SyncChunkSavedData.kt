package calebxzau.rdi.mc.syncchunk.server

import calebxzau.rdi.mc.syncchunk.SyncChunkAddResult
import calebxzau.rdi.mc.syncchunk.SyncChunkEntry
import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import calebxzau.rdi.mc.syncchunk.SyncChunkRemoveResult
import calebxzau.rdi.mc.syncchunk.SyncChunkSaveFormat
import calebxzau.rdi.mc.syncchunk.SyncChunkState
import calebxzau.rdi.mc.syncchunk.SyncChunkStorage
import calebxzau.rdi.mc.syncchunk.SyncChunkStore
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.storage.DimensionDataStorage
import org.slf4j.LoggerFactory
import java.nio.file.Path
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
        private val logger = LoggerFactory.getLogger(SyncChunkSavedData::class.java)

        /** Must run on the server thread. */
        fun of(server: MinecraftServer): Result<SyncChunkSavedData> = runCatching {
            getOrCreate(server.overworld().dataStorage, SyncChunkSaveFormat.dataFile(server))
        }.onFailure { exception ->
            logger.error("Failed to load SyncChunk data", exception)
        }

        internal fun getOrCreate(storage: DimensionDataStorage, dataFile: Path): SyncChunkSavedData {
            val factory = factory()
            return SyncChunkStorage.loadOrCreate(
                dataFile,
                { storage.get(factory, SyncChunkStorage.FILE_ID) },
                { storage.computeIfAbsent(factory, SyncChunkStorage.FILE_ID) },
            )
        }

        fun factory(): Factory<SyncChunkSavedData> = Factory(
            { SyncChunkSavedData(SyncChunkState.empty()) },
            { tag, _ -> SyncChunkSavedData(SyncChunkSaveFormat.read(tag)) },
        )
    }
}
