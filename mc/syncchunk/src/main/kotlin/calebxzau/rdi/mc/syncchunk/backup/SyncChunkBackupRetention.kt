package calebxzau.rdi.mc.syncchunk.backup

/**
 * Which points to keep: everything from the last 24 hours, then the first automatic point of each
 * hour and every before-rollback point for 7 days. The newest point and [keep]'s `pinned` points (those
 * a pending rollback needs) are always kept.
 */
object SyncChunkBackupRetention {
    const val HOUR_MS = 60L * 60 * 1000
    const val ALL_POINTS_MS = 24 * HOUR_MS
    const val MAX_AGE_MS = 7 * 24 * HOUR_MS

    fun keep(points: Collection<SyncChunkBackupPointSummary>, now: Long, pinned: Set<String> = emptySet()): Set<String> {
        val newest = points.maxWithOrNull(POINT_ORDER) ?: return emptySet()
        val kept = HashSet<String>()
        kept += newest.id
        val firstOfHour = HashSet<Long>()
        points.sortedWith(POINT_ORDER).forEach { point ->
            val age = now - point.createdAt
            when {
                point.id in pinned -> kept += point.id
                age < ALL_POINTS_MS -> kept += point.id
                age >= MAX_AGE_MS -> Unit
                point.kind == SyncChunkBackupKind.BeforeRollback -> kept += point.id
                firstOfHour.add(Math.floorDiv(point.createdAt, HOUR_MS)) -> kept += point.id
            }
        }
        return kept
    }

    /** Oldest first; UUIDv7 ids break ties between points of the same millisecond. */
    val POINT_ORDER: Comparator<SyncChunkBackupPointSummary> = compareBy({ it.createdAt }, { it.id })
}
