package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DmRegionChangeLogTest {
    /** Slot 1 of `region/r.0.0.mca` is overworld chunk (1, 0). */
    private fun activateKeepingSlotOne(root: java.nio.file.Path, budget: Long) {
        DmRegionChangeLog.activate(root, budget)
        DmRegionChangeLog.setKeep(setOf(SyncChunkKey("minecraft:overworld", 1, 0)))
    }

    @Test
    fun onlyKeptChunksAndUnknownRegionFilesAreRecorded() {
        val root = Files.createTempDirectory("dm-change-log")
        try {
            activateKeepingSlotOne(root, 1024)
            assertFalse(DmRegionChangeLog.recordWrite("region/r.0.0.mca", 2, 2, 1, byteArrayOf(2)))
            assertFalse(DmRegionChangeLog.recordWrite("DIM-1/region/r.0.0.mca", 1, 2, 1, byteArrayOf(2)))
            assertTrue(DmRegionChangeLog.recordWrite("entities/r.0.0.mca", 1, 2, 1, byteArrayOf(2)))
            assertTrue(DmRegionChangeLog.recordWrite("data/somemod/r.0.0.mca", 7, 2, 1, byteArrayOf(2)))
            DmRegionChangeLog.releaseReconcile()
            assertEquals(setOf("entities/r.0.0.mca", "data/somemod/r.0.0.mca"), DmRegionChangeLog.drain().map { it.key.region }.toSet())
        } finally {
            DmRegionChangeLog.discard()
        }
    }

    @Test
    fun latestHookWinsAndScanDoesNotReplaceIt() {
        val root = Files.createTempDirectory("dm-change-log")
        try {
            activateKeepingSlotOne(root, 1024)
            DmRegionChangeLog.recordWrite("region/r.0.0.mca", 1, 2, 1, byteArrayOf(2))
            assertTrue(!DmRegionChangeLog.recordScan(DmRegionChange(DmRegionChunkKey("region/r.0.0.mca", 1), 1, byteArrayOf(1), 1)))
            DmRegionChangeLog.releaseReconcile()
            assertEquals(2, DmRegionChangeLog.drain().single().bytes!!.single().toInt())
        } finally {
            DmRegionChangeLog.discard()
        }
    }

    @Test
    fun heldModeAndMergeBackPreserveNewerHook() {
        val root = Files.createTempDirectory("dm-change-log")
        try {
            activateKeepingSlotOne(root, 1024)
            DmRegionChangeLog.recordWrite("region/r.0.0.mca", 1, 2, 1, byteArrayOf(2))
            assertTrue(DmRegionChangeLog.drain().isEmpty())
            DmRegionChangeLog.releaseReconcile()
            val drained = DmRegionChangeLog.drain()
            DmRegionChangeLog.recordWrite("region/r.0.0.mca", 1, 3, 1, byteArrayOf(3))
            DmRegionChangeLog.mergeBack(drained)
            assertEquals(3, DmRegionChangeLog.drain().single().bytes!!.single().toInt())
        } finally {
            DmRegionChangeLog.discard()
        }
    }

    @Test
    fun overflowRequiresReconcileAndReleasesMemory() {
        val root = Files.createTempDirectory("dm-change-log")
        try {
            activateKeepingSlotOne(root, 2)
            DmRegionChangeLog.recordWrite("region/r.0.0.mca", 1, 2, 1, byteArrayOf(1, 2, 3))
            assertTrue(DmRegionChangeLog.needsReconcile())
            assertEquals(0, DmRegionChangeLog.pendingCount())
        } finally {
            DmRegionChangeLog.discard()
        }
    }
}
