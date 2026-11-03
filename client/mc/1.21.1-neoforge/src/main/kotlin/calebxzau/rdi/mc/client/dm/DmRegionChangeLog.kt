package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import java.nio.file.Path

data class DmRegionChunkKey(val region: String, val index: Int)

data class DmRegionChange(
    val key: DmRegionChunkKey,
    val timestamp: Int,
    val bytes: ByteArray?,
    val sectors: Int,
) {
    val sizeBytes: Long get() = (bytes?.size ?: 0).toLong()
}

/** Thread-safe latest-wins journal for RegionFile writes observed by the mixin. */
object DmRegionChangeLog {
    const val DEFAULT_BYTE_BUDGET: Long = 256L * 1024L * 1024L
    private val lock = Any()
    private val changes = LinkedHashMap<DmRegionChunkKey, DmRegionChange>()
    @Volatile private var worldRoot: Path? = null
    @Volatile private var held = false
    @Volatile private var reconcileRequired = false
    /** Chunks whose writes are recorded; the checkpoint reads any other kept chunk from disk. */
    @Volatile private var keep: Set<SyncChunkKey> = emptySet()
    private var bytes = 0L
    private var byteBudget = DEFAULT_BYTE_BUDGET

    @JvmStatic
    fun activate(root: Path, budget: Long = DEFAULT_BYTE_BUDGET) {
        synchronized(lock) {
            worldRoot = root.toAbsolutePath().normalize()
            held = true
            reconcileRequired = false
            changes.clear()
            bytes = 0L
            byteBudget = budget.coerceAtLeast(1L)
            keep = emptySet()
        }
    }

    @JvmStatic fun setKeep(chunks: Set<SyncChunkKey>) { keep = chunks }

    /** True when a write to slot [index] of [region] should be recorded. */
    @JvmStatic fun wants(region: String, index: Int): Boolean = DmCheckpointKeepSet.keeps(keep, region, index)

    @JvmStatic fun isActive(): Boolean = worldRoot != null
    @JvmStatic fun activeWorldRoot(): Path? = worldRoot
    @JvmStatic fun isUnderActiveWorld(path: Path): Boolean {
        val root = worldRoot ?: return false
        return runCatching { path.toAbsolutePath().normalize().startsWith(root) }.getOrDefault(false)
    }

    @JvmStatic
    fun recordWrite(region: String, index: Int, timestamp: Int, sectors: Int, bytes: ByteArray) =
        wants(region, index) && put(DmRegionChange(DmRegionChunkKey(region, index), timestamp, bytes.copyOf(), sectors), overwrite = true)

    @JvmStatic
    fun recordClear(region: String, index: Int, timestamp: Int) =
        wants(region, index) && put(DmRegionChange(DmRegionChunkKey(region, index), timestamp, null, 0), overwrite = true)

    fun recordScan(change: DmRegionChange): Boolean = put(change, overwrite = false)

    private fun put(change: DmRegionChange, overwrite: Boolean): Boolean {
        synchronized(lock) {
            if (worldRoot == null) return false
            if (!overwrite && changes.containsKey(change.key)) return false
            val previous = changes[change.key]
            val nextBytes = bytes - (previous?.sizeBytes ?: 0L) + change.sizeBytes
            if (nextBytes > byteBudget) {
                changes.clear()
                bytes = 0L
                reconcileRequired = true
                return false
            }
            changes[change.key] = change
            bytes = nextBytes
            return true
        }
    }

    @JvmStatic
    fun releaseReconcile() {
        synchronized(lock) { held = false }
    }

    @JvmStatic
    fun markNeedsReconcile() {
        synchronized(lock) { reconcileRequired = true }
    }

    @JvmStatic
    fun needsReconcile(): Boolean = synchronized(lock) { reconcileRequired }
    @JvmStatic fun clearNeedsReconcile() = synchronized(lock) { reconcileRequired = false }

    @JvmStatic
    fun drain(): List<DmRegionChange> = synchronized(lock) {
        if (held) return@synchronized emptyList()
        val result = changes.values.toList()
        changes.clear()
        bytes = 0L
        result
    }

    @JvmStatic
    fun mergeBack(entries: Collection<DmRegionChange>) {
        entries.forEach { put(it, overwrite = false) }
    }

    @JvmStatic
    fun discard() {
        synchronized(lock) {
            worldRoot = null
            held = false
            reconcileRequired = false
            changes.clear()
            bytes = 0L
            keep = emptySet()
        }
    }

    internal fun pendingBytes(): Long = synchronized(lock) { bytes }
    @JvmStatic fun pendingCount(): Int = synchronized(lock) { changes.size }
    internal fun isHeld(): Boolean = synchronized(lock) { held }
}
