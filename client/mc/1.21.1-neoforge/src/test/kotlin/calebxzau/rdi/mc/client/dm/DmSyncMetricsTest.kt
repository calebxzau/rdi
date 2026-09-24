package calebxzau.rdi.mc.client.dm

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DmSyncMetricsTest {
    @Test
    fun emitsExactlyOneStartAndEndAndNullForUnrunStages(): Unit {
        val lines = mutableListOf<String>()
        val metrics = DmSyncMetrics(UUID.randomUUID(), UUID.randomUUID(), lines::add)

        metrics.begin()
        metrics.begin()
        metrics.measure(DmSyncMetrics.STATUS) { Unit }
        metrics.uploadZipBytes = 128L
        metrics.snapshotExpandedBytes = 4096L
        metrics.report(DmWorldSyncResult.Failed, committed = false, reason = "test")
        metrics.report(DmWorldSyncResult.Success, committed = true)

        assertEquals(1, lines.count { it.contains("DM_WORLD_SYNC_START") })
        assertEquals(1, lines.count { it.contains("DM_WORLD_SYNC_END") })
        val end = lines.single { it.contains("DM_WORLD_SYNC_END") }
        assertTrue(end.contains("statusMs="))
        assertTrue(end.contains("readinessWaitMs=null"))
        assertTrue(end.contains("uploadMs=null"))
        assertTrue(end.contains("uploadZipBytes=128"))
        assertTrue(end.contains("snapshotExpandedBytes=4096"))
        assertTrue(!end.contains("archiveZipBytes"))
        assertTrue(end.contains("result=Failed"))
        assertTrue(metrics.hasReported())
    }
}
