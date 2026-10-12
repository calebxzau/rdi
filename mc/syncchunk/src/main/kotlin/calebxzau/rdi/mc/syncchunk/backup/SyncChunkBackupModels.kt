package calebxzau.rdi.mc.syncchunk.backup

import calebxzau.rdi.mc.syncchunk.SyncChunkKey

/** Why a backup point (回档点) was taken. */
enum class SyncChunkBackupKind {
    /** Taken right after Minecraft's 5-minute autosave. */
    Auto,

    /** Taken before a rollback is applied, so the rollback itself can be undone. */
    BeforeRollback,
}

/** One captured part of a chunk; [hash] decides whether it changed, [raw] is stored when it is new. */
sealed interface SyncChunkBackupPart {
    class Present(val hash: String, val raw: ByteArray) : SyncChunkBackupPart

    /** Minecraft has nothing stored for this part, e.g. a chunk without entities. */
    data object Absent : SyncChunkBackupPart

    /** Reading failed; the previous point's part is kept so a transient error never looks like a deletion. */
    data object Unavailable : SyncChunkBackupPart
}

/** Object hashes of one sync chunk; null means Minecraft stores nothing for that part. */
data class SyncChunkBackupChunk(
    val key: SyncChunkKey,
    val terrain: String?,
    val entities: String?,
    val poi: String?,
)

/** One backed-up world file; [path] is relative to the world directory and uses `/`. */
data class SyncChunkBackupFile(val path: String, val hash: String, val size: Long)

/** Counts compared with the previous point. */
data class SyncChunkBackupChanges(
    val terrainChunks: Int,
    val entityChunks: Int,
    val poiChunks: Int,
    val players: Int,
    val files: Int,
)

/** Full content of `points/<id>.json`. */
data class SyncChunkBackupPoint(
    val id: String,
    val kind: SyncChunkBackupKind,
    val createdAt: Long,
    val gameTime: Long,
    val chunks: List<SyncChunkBackupChunk>,
    val files: List<SyncChunkBackupFile>,
    val changed: SyncChunkBackupChanges,
    val addedBytes: Long,
    val rollbackId: String? = null,
) {
    /** Every object this point needs. */
    fun objectHashes(): Set<String> = buildSet {
        chunks.forEach { chunk -> listOfNotNull(chunk.terrain, chunk.entities, chunk.poi).forEach(::add) }
        files.forEach { add(it.hash) }
    }

    fun playerCount(): Int = files.count { SyncChunkBackupFileRules.playerDataUuid(it.path) != null }

    fun summary(): SyncChunkBackupPointSummary = SyncChunkBackupPointSummary(
        id = id,
        kind = kind,
        createdAt = createdAt,
        gameTime = gameTime,
        chunkCount = chunks.size,
        playerCount = playerCount(),
        changed = changed,
        addedBytes = addedBytes,
        rollbackId = rollbackId,
    )
}

/** One entry of `index.json`. */
data class SyncChunkBackupPointSummary(
    val id: String,
    val kind: SyncChunkBackupKind,
    val createdAt: Long,
    val gameTime: Long,
    val chunkCount: Int,
    val playerCount: Int,
    val changed: SyncChunkBackupChanges,
    val addedBytes: Long,
    val rollbackId: String?,
)

/** Content of `index.json`; points are newest first. */
data class SyncChunkBackupIndex(
    val worldToken: String,
    val updatedAt: Long,
    val usedBytes: Long,
    val maxBytes: Long,
    val points: List<SyncChunkBackupPointSummary>,
)
