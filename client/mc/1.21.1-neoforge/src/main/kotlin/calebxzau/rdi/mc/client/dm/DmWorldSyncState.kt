package calebxzau.rdi.mc.client.dm

import java.util.UUID

/** One WorldData file as it appears in a v5 manifest. */
data class DmWorldFileEntry(val path: String, val bytes: Long, val sha1: String)

/** One synchronized column as it appears in a v5 manifest. */
data class DmWorldColumnEntry(
    val column: DmSnapshotArchive.Column,
    val entitiesPresent: Boolean,
    val poiPresent: Boolean,
    val sha1: String,
)

/** Per-room WorldData exclusion policy. Exact paths only; the first version has no globs. */
data class DmWorldSyncPolicy(
    val excludedFiles: Set<String> = emptySet(),
    val excludedDirectories: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY = DmWorldSyncPolicy()
    }
}

/**
 * A base manifest pinned to one specific published snapshot.
 *
 * It is always read from a fixed `snapshotId`, never from a mutable "latest" pointer, so
 * that the incremental decision and Master's reuse rule refer to the same archive.
 */
data class DmWorldSyncBaseline(
    val snapshotId: UUID,
    val revision: Long,
    val files: Map<String, DmWorldFileEntry>,
    val directories: Set<String>,
    val excludedFiles: Set<String>,
    val excludedDirectories: Set<String>,
    val columns: Map<DmSnapshotArchive.Column, DmWorldColumnEntry>,
)

/** What this cycle has to transmit relative to its base. */
data class DmWorldSyncPlan(
    val changedFiles: List<String>,
    val removedFiles: List<String>,
    val updatedColumns: List<DmSnapshotArchive.Column>,
    val inheritedColumns: List<DmSnapshotArchive.Column>,
    val hasChanges: Boolean,
)

/** Terminal classification of one synchronization cycle. */
enum class DmWorldSyncResult { Success, NoChanges, Partial, Failed, Cancelled }

internal data class DmWorldReadySelection<T>(
    val ready: List<T>,
    val blockers: List<DmSnapshotMemoryCapture.ReadinessBlocker>,
)

/**
 * Pure, Minecraft-independent synchronization rules.
 *
 * Everything here is deterministic so the incremental decision, the roster guard, and the
 * result classification can be tested without a running server.
 */
internal object DmWorldSyncState {
    /**
     * Partitions columns into the ones that can be captured now and the ones still in
     * transition. Unlike the readiness pass, an empty ready set is not an error here: a
     * cycle still has to synchronize WorldData when no column is available.
     */
    fun <T> selectReady(
        columns: List<T>,
        preflight: (T) -> Unit,
    ): DmWorldReadySelection<T> {
        if (columns.isEmpty()) return DmWorldReadySelection(emptyList(), emptyList())
        val ready = ArrayList<T>(columns.size)
        val blockers = ArrayList<DmSnapshotMemoryCapture.ReadinessBlocker>()
        columns.forEach { column ->
            try {
                preflight(column)
                ready += column
            } catch (deferred: DmSnapshotMemoryCapture.Deferred) {
                blockers += deferred.blockers
            }
        }
        return DmWorldReadySelection(ready, blockers.distinctBy { it.coordinate to it.reason })
    }

    fun <T> sameRoster(
        expectedRevision: Long,
        expectedColumns: List<T>,
        actualRevision: Long,
        actualColumns: List<T>,
    ): Boolean = expectedRevision == actualRevision && expectedColumns == actualColumns

    /**
     * Decides which members this cycle must transmit.
     *
     * A file or column is transmitted when the base does not contain it or when its digest
     * differs. Without a base every observed member is transmitted, and the cycle can never
     * be classified as unchanged.
     */
    fun plan(
        base: DmWorldSyncBaseline?,
        revision: Long,
        files: List<DmWorldFileEntry>,
        directories: List<String>,
        policy: DmWorldSyncPolicy,
        columns: List<DmWorldColumnEntry>,
    ): DmWorldSyncPlan {
        val changedFiles = ArrayList<String>()
        files.forEach { file ->
            val previous = base?.files?.get(file.path)
            if (previous == null || previous.bytes != file.bytes || previous.sha1 != file.sha1) {
                changedFiles += file.path
            }
        }
        val currentPaths = files.mapTo(HashSet()) { it.path }
        val removedFiles = base?.files?.keys?.filterNot { it in currentPaths }?.sorted() ?: emptyList()

        val updatedColumns = ArrayList<DmSnapshotArchive.Column>()
        val inheritedColumns = ArrayList<DmSnapshotArchive.Column>()
        val revisionMatches = base?.revision == revision
        columns.forEach { entry ->
            val previous = base?.columns?.get(entry.column)
            val unchanged = revisionMatches && previous != null &&
                previous.sha1 == entry.sha1 &&
                previous.entitiesPresent == entry.entitiesPresent &&
                previous.poiPresent == entry.poiPresent
            if (unchanged) inheritedColumns += entry.column else updatedColumns += entry.column
        }

        val hasChanges = base == null ||
            base.revision != revision ||
            changedFiles.isNotEmpty() ||
            removedFiles.isNotEmpty() ||
            updatedColumns.isNotEmpty() ||
            base.directories != directories.toSet() ||
            base.excludedFiles != policy.excludedFiles ||
            base.excludedDirectories != policy.excludedDirectories
        return DmWorldSyncPlan(
            changedFiles.sorted(),
            removedFiles,
            updatedColumns.sortedWith(COLUMN_ORDER),
            inheritedColumns.sortedWith(COLUMN_ORDER),
            hasChanges,
        )
    }

    /**
     * Classifies a committed cycle. `Partial` means the archive was published but this
     * cycle could not observe every roster column, or Master stores fewer columns than the
     * roster currently has.
     */
    fun classify(deferredColumns: Int, storedColumns: Int, totalColumns: Int): DmWorldSyncResult =
        if (deferredColumns > 0 || storedColumns < totalColumns) {
            DmWorldSyncResult.Partial
        } else {
            DmWorldSyncResult.Success
        }

    private val COLUMN_ORDER = compareBy<DmSnapshotArchive.Column> { it.dimensionId }
        .thenBy { it.chunkX }
        .thenBy { it.chunkZ }
}
