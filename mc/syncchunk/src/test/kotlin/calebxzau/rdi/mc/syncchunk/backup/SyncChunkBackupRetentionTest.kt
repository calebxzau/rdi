package calebxzau.rdi.mc.syncchunk.backup

import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupRetention.HOUR_MS
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncChunkBackupRetentionTest {
    private val now = 1_000L * HOUR_MS
    private val minute = 60_000L

    @Test
    fun keepsEveryPointOfTheLastDay() {
        val points = (0 until 288).map { point("p${it}", now - it * 5 * minute) }
        assertEquals(points.map { it.id }.toSet(), SyncChunkBackupRetention.keep(points, now))
    }

    @Test
    fun keepsTheFirstPointOfEachHourUpToSevenDays() {
        val hourStart = (now - 30 * HOUR_MS) / HOUR_MS * HOUR_MS
        val first = point("first", hourStart + 5 * minute)
        val later = point("later", hourStart + 35 * minute)
        val nextHour = point("next", hourStart + HOUR_MS + 10 * minute)
        val old = point("old", now - 7 * 24 * HOUR_MS)
        val newest = point("newest", now)

        assertEquals(
            setOf("first", "next", "newest"),
            SyncChunkBackupRetention.keep(listOf(later, old, first, nextHour, newest), now),
        )
    }

    @Test
    fun keepsBeforeRollbackPointsForSevenDays() {
        val hourStart = (now - 30 * HOUR_MS) / HOUR_MS * HOUR_MS
        val auto = point("auto", hourStart + minute)
        val beforeRollback = point("before", hourStart + 2 * minute, SyncChunkBackupKind.BeforeRollback)
        val newest = point("newest", now)

        assertEquals(setOf("auto", "before", "newest"), SyncChunkBackupRetention.keep(listOf(auto, beforeRollback, newest), now))
    }

    @Test
    fun alwaysKeepsTheNewestPointEvenWhenEverythingIsOld() {
        val points = listOf(point("a", now - 30 * 24 * HOUR_MS), point("b", now - 20 * 24 * HOUR_MS))
        assertEquals(setOf("b"), SyncChunkBackupRetention.keep(points, now))
        assertEquals(emptySet(), SyncChunkBackupRetention.keep(emptyList(), now))
    }

    @Test
    fun pinnedPointsAreKeptAtAnyAge() {
        val pinned = point("pinned", now - 30 * 24 * HOUR_MS)
        val newest = point("newest", now)
        assertEquals(setOf("pinned", "newest"), SyncChunkBackupRetention.keep(listOf(pinned, newest), now, setOf("pinned", "gone")))
    }

    private fun point(id: String, createdAt: Long, kind: SyncChunkBackupKind = SyncChunkBackupKind.Auto) =
        SyncChunkBackupPointSummary(id, kind, createdAt, 0, 0, 0, SyncChunkBackupChanges(0, 0, 0, 0, 0), 0, null)
}
