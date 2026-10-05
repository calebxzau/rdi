package calebxzau.rdi.mc.syncchunk

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** A server's saved sync chunk selections; all calls run on the server thread. */
interface SyncChunkStore {
    fun add(key: SyncChunkKey, ownerId: UUID): SyncChunkAddResult

    fun remove(key: SyncChunkKey, ownerId: UUID): SyncChunkRemoveResult

    fun snapshot(): List<SyncChunkEntry>
}

object SyncChunkStorage {
    /** Saved once for all dimensions as `<world>/data/rdi_sync_chunks.dat`. */
    const val FILE_ID = "rdi_sync_chunks"

    /**
     * Minecraft logs and returns null for unreadable data files, so a new list is created only when
     * [dataFile] is confirmed absent; otherwise the existing file stays untouched until it is repaired.
     */
    fun <T : Any> loadOrCreate(dataFile: Path, load: () -> T?, create: () -> T): T {
        load()?.let { return it }
        check(Files.notExists(dataFile)) { "同步区块存档${dataFile}无法读取，功能不可用以防覆盖" }
        return create()
    }
}
