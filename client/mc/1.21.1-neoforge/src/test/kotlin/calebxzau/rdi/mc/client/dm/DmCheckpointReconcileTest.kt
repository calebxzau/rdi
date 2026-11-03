package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DmCheckpointReconcileTest {
    private val region = "region/r.0.0.mca"
    private val keep = setOf(SyncChunkKey("minecraft:overworld", 1, 0))

    private fun table(vararg slots: Pair<Int, DmRegionSlot>): DmRegionTable =
        DmRegionTable(List(1024) { index -> slots.toMap()[index] ?: DmRegionSlot(0, 0) })

    private fun entry(region: String, index: Int, timestamp: Int) =
        DmRegionChange(DmRegionChunkKey(region, index), timestamp, byteArrayOf(timestamp.toByte()), 1)

    private fun plan(
        remote: Map<String, DmRegionTable>,
        local: Map<String, DmRegionTable>,
        logged: List<DmRegionChange> = emptyList(),
        readLocal: (String, Int) -> DmRegionChange? = { r, i -> entry(r, i, local.getValue(r).slots[i].timestamp) },
    ) = DmCheckpointReconcile.plan(keep, logged, remote, local.keys, local::get, readLocal).associateBy { it.key }

    @Test
    fun keptChunkMissingOnTheMasterIsReadFromDisk() {
        val result = plan(remote = emptyMap(), local = mapOf(region to table(1 to DmRegionSlot(5, 1), 2 to DmRegionSlot(5, 1))))
        assertEquals(setOf(DmRegionChunkKey(region, 1)), result.keys)
        assertEquals(5, result.values.single().timestamp)
    }

    @Test
    fun matchingSlotsUploadNothing() {
        val same = table(1 to DmRegionSlot(5, 1))
        assertTrue(plan(remote = mapOf(region to same), local = mapOf(region to same)).isEmpty())
    }

    @Test
    fun masterChunksOutsideTheKeepSetAreCleared() {
        val result = plan(remote = mapOf(region to table(1 to DmRegionSlot(5, 1), 7 to DmRegionSlot(5, 2))), local = mapOf(region to table(1 to DmRegionSlot(5, 1))))
        val clear = result.getValue(DmRegionChunkKey(region, 7))
        assertEquals(null, clear.bytes)
        assertEquals(1, result.size)
    }

    @Test
    fun keptChunkGoneLocallyIsClearedAndAChunkChangingMidReadWaits() {
        val cleared = plan(remote = mapOf(region to table(1 to DmRegionSlot(5, 1))), local = emptyMap())
        assertEquals(null, cleared.getValue(DmRegionChunkKey(region, 1)).bytes)
        val racing = plan(remote = emptyMap(), local = mapOf(region to table(1 to DmRegionSlot(6, 1)))) { _, _ -> null }
        assertTrue(racing.isEmpty())
    }

    @Test
    fun loggedWritesWinAndWritesOutsideTheKeepSetAreDropped() {
        val logged = listOf(entry(region, 1, 9), entry(region, 2, 9))
        val result = plan(remote = emptyMap(), local = mapOf(region to table(1 to DmRegionSlot(8, 1))), logged = logged)
        assertEquals(setOf(DmRegionChunkKey(region, 1)), result.keys)
        assertEquals(9, result.values.single().timestamp)
    }

    @Test
    fun unknownRegionFilesMirrorWhole() {
        val modRegion = "data/somemod/r.0.0.mca"
        val result = plan(
            remote = mapOf(modRegion to table(4 to DmRegionSlot(1, 1))),
            local = mapOf(modRegion to table(3 to DmRegionSlot(2, 1))),
        )
        assertEquals(setOf(DmRegionChunkKey(modRegion, 3), DmRegionChunkKey(modRegion, 4)), result.keys)
        assertEquals(null, result.getValue(DmRegionChunkKey(modRegion, 4)).bytes)
    }
}
