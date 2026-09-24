package calebxzau.rdi.mc.client.dm

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DmWorldSyncStateTest {
    private val snapshotId = UUID.fromString("018f0f4d-4b2e-7abc-8def-0123456789ab")
    private val column = DmSnapshotArchive.Column("minecraft:overworld", 0, 0)

    @Test
    fun initialPlanTransmitsAllObservedFilesAndColumns(): Unit {
        val plan = DmWorldSyncState.plan(
            null,
            1,
            listOf(file("level.dat", "a")),
            emptyList(),
            DmWorldSyncPolicy.EMPTY,
            listOf(column("a")),
        )
        assertTrue(plan.hasChanges)
        assertEquals(listOf("level.dat"), plan.changedFiles)
        assertEquals(listOf(column), plan.updatedColumns)
        assertTrue(plan.inheritedColumns.isEmpty())
    }

    @Test
    fun noChangePlanInheritsSameFilesColumnsAndDirectories(): Unit {
        val baseline = baseline()
        val plan = DmWorldSyncState.plan(
            baseline,
            4,
            listOf(file("level.dat", "a"), file("old.dat", "old")),
            listOf("empty"),
            DmWorldSyncPolicy.EMPTY,
            listOf(column("a")),
        )
        assertFalse(plan.hasChanges)
        assertTrue(plan.changedFiles.isEmpty())
        assertEquals(listOf(column), plan.inheritedColumns)
    }

    @Test
    fun fileModifyRemoveDirectoryPolicyAndRevisionChangesArePlanned(): Unit {
        val baseline = baseline()
        val plan = DmWorldSyncState.plan(
            baseline,
            5,
            listOf(file("level.dat", "changed"), file("new.dat", "new")),
            listOf("new-empty"),
            DmWorldSyncPolicy(setOf("new.dat"), setOf("new-empty")),
            listOf(column("changed")),
        )
        assertTrue(plan.hasChanges)
        assertEquals(listOf("level.dat", "new.dat"), plan.changedFiles)
        assertEquals(listOf("old.dat"), plan.removedFiles)
        assertEquals(listOf(column), plan.updatedColumns)
    }

    @Test
    fun revisionChangeForcesIdenticalObservedColumnToBeUpdated(): Unit {
        val plan = DmWorldSyncState.plan(
            baseline(),
            5,
            listOf(file("level.dat", "a"), file("old.dat", "old")),
            listOf("empty"),
            DmWorldSyncPolicy.EMPTY,
            listOf(column("a")),
        )
        assertTrue(plan.hasChanges)
        assertEquals(listOf(column), plan.updatedColumns)
        assertTrue(plan.inheritedColumns.isEmpty())
    }

    @Test
    fun emptyAndDeferredClassificationPreserveWorldDataCycle(): Unit {
        val empty = DmWorldSyncState.plan(
            baseline(),
            4,
            listOf(file("level.dat", "a")),
            listOf("empty"),
            DmWorldSyncPolicy.EMPTY,
            emptyList(),
        )
        assertTrue(empty.hasChanges)
        assertEquals(DmWorldSyncResult.Partial, DmWorldSyncState.classify(1, 0, 1))
        assertEquals(DmWorldSyncResult.Success, DmWorldSyncState.classify(0, 0, 0))
    }

    private fun baseline() = DmWorldSyncBaseline(
        snapshotId,
        4,
        mapOf("level.dat" to file("level.dat", "a"), "old.dat" to file("old.dat", "old")),
        setOf("empty"),
        emptySet(),
        emptySet(),
        mapOf(column to column("a")),
    )

    private fun file(path: String, sha1: String) = DmWorldFileEntry(path, 1, sha1.repeat(40).take(40))

    private fun column(sha1: String) = DmWorldColumnEntry(column, false, false, sha1.repeat(40).take(40))
}
