package calebxzau.rdi.mc.client.dm

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DmRegionChangeLogTest {
    @Test
    fun latestHookWinsAndScanDoesNotReplaceIt() {
        val root = Files.createTempDirectory("dm-change-log")
        try {
            DmRegionChangeLog.activate(root, 1024)
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
            DmRegionChangeLog.activate(root, 1024)
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
            DmRegionChangeLog.activate(root, 2)
            DmRegionChangeLog.recordWrite("region/r.0.0.mca", 1, 2, 1, byteArrayOf(1, 2, 3))
            assertTrue(DmRegionChangeLog.needsReconcile())
            assertEquals(0, DmRegionChangeLog.pendingCount())
        } finally {
            DmRegionChangeLog.discard()
        }
    }
}
