package calebxzau.rdi.mc.syncchunk

import java.util.UUID

data class SyncChunkKey(val dimensionId: String, val chunkX: Int, val chunkZ: Int) {
    init {
        require(dimensionId.isNotBlank()) { "区块维度不能为空" }
    }
}

data class SyncChunkEntry(val key: SyncChunkKey, val ownerId: UUID)

enum class SyncChunkAddResult {
    Added,
    AlreadyPresent,
    OccupiedByOther,
    QuotaReached,
}

enum class SyncChunkRemoveResult {
    Removed,
    AlreadyAbsent,
    OwnedByOther,
}

/** Server-thread-owned state for a world's sync chunk selections. */
class SyncChunkState private constructor(
    initialEntries: Collection<SyncChunkEntry>,
    val maxTotal: Int,
) {
    private val entries = LinkedHashMap<SyncChunkKey, SyncChunkEntry>()

    init {
        require(maxTotal > 0)
        initialEntries.forEach { entry ->
            require(entries.putIfAbsent(entry.key, entry) == null) { "同步区块存档包含重复区块：${entry.key}" }
        }
        require(entries.size <= maxTotal) { "同步区块存档超过数量上限：${entries.size}/$maxTotal" }
    }

    fun add(key: SyncChunkKey, ownerId: UUID): SyncChunkAddResult {
        val existing = entries[key]
        if (existing != null) {
            return if (existing.ownerId == ownerId) SyncChunkAddResult.AlreadyPresent else SyncChunkAddResult.OccupiedByOther
        }
        if (entries.size >= maxTotal) return SyncChunkAddResult.QuotaReached
        entries[key] = SyncChunkEntry(key, ownerId)
        return SyncChunkAddResult.Added
    }

    fun remove(key: SyncChunkKey, ownerId: UUID): SyncChunkRemoveResult {
        val existing = entries[key] ?: return SyncChunkRemoveResult.AlreadyAbsent
        if (existing.ownerId != ownerId) return SyncChunkRemoveResult.OwnedByOther
        entries.remove(key)
        return SyncChunkRemoveResult.Removed
    }

    fun ownerOf(key: SyncChunkKey): UUID? = entries[key]?.ownerId

    fun snapshot(): List<SyncChunkEntry> = entries.values.sortedWith(ENTRY_ORDER)

    val size: Int get() = entries.size

    companion object {
        const val DEFAULT_MAX_TOTAL = 256

        private val ENTRY_ORDER = compareBy<SyncChunkEntry>({ it.key.dimensionId }, { it.key.chunkX }, { it.key.chunkZ }, { it.ownerId.toString() })

        fun empty(maxTotal: Int = DEFAULT_MAX_TOTAL): SyncChunkState = SyncChunkState(emptyList(), maxTotal)

        fun restore(entries: Collection<SyncChunkEntry>, maxTotal: Int = DEFAULT_MAX_TOTAL): SyncChunkState =
            SyncChunkState(entries, maxTotal)
    }
}
