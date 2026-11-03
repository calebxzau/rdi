package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DmCheckpointKeepSetTest {
    private val overworld = "minecraft:overworld"

    @Test
    fun regionPathsMapToDimensionAndChunk() {
        assertEquals(SyncChunkKey(overworld, 33, -31), DmCheckpointKeepSet.chunkOf("region/r.1.-1.mca", 33))
        assertEquals(SyncChunkKey("minecraft:the_nether", -1, -32), DmCheckpointKeepSet.chunkOf("DIM-1/entities/r.-1.-1.mca", 31))
        assertEquals(SyncChunkKey("minecraft:the_end", 0, 0), DmCheckpointKeepSet.chunkOf("DIM1/poi/r.0.0.mca", 0))
        assertEquals(SyncChunkKey("twilightforest:twilight_forest", 5, 2), DmCheckpointKeepSet.chunkOf("dimensions/twilightforest/twilight_forest/region/r.0.0.mca", 69))
        assertEquals(SyncChunkKey("mod:a/b", 0, 0), DmCheckpointKeepSet.chunkOf("dimensions/mod/a/b/region/r.0.0.mca", 0))
    }

    @Test
    fun filesOutsideVanillaChunkFoldersAreNotChunkRegions() {
        assertNull(DmCheckpointKeepSet.chunkOf("data/somemod/r.0.0.mca", 0))
        assertNull(DmCheckpointKeepSet.chunkOf("region/c.0.0.mca", 0))
        assertNull(DmCheckpointKeepSet.chunkOf("DIM2/region/r.0.0.mca", 0))
        assertFalse(DmCheckpointKeepSet.isChunkRegion("dimensions/mod/region/r.0.0.mca"))
        assertTrue(DmCheckpointKeepSet.keeps(emptySet(), "data/somemod/r.0.0.mca", 5))
    }

    @Test
    fun slotsRoundTripThroughEveryChunkFolder() {
        val chunks = listOf(
            SyncChunkKey(overworld, -1, -1),
            SyncChunkKey(overworld, 31, 32),
            SyncChunkKey("minecraft:the_nether", -33, 64),
            SyncChunkKey("mod:a/b", 7, -9),
        )
        chunks.forEach { chunk ->
            val slots = DmCheckpointKeepSet.slotsOf(chunk)
            assertEquals(listOf("entities", "poi", "region"), slots.map { it.first.split('/').dropLast(1).last() })
            slots.forEach { (region, index) -> assertEquals(chunk, DmCheckpointKeepSet.chunkOf(region, index)) }
        }
        assertEquals("region/r.-1.-1.mca" to 1023, DmCheckpointKeepSet.slotsOf(SyncChunkKey(overworld, -1, -1)).last())
    }

    @Test
    fun playersKeepTheirThreeByThreeAreaAcrossRegionBorders() {
        val keep = DmCheckpointKeepSet.build(
            syncChunks = listOf(SyncChunkKey(overworld, 100, 100)),
            players = listOf(SyncChunkKey(overworld, 0, 0), SyncChunkKey("minecraft:the_nether", 0, 0)),
        )
        assertEquals(1 + 9 + 9, keep.size)
        assertTrue(SyncChunkKey(overworld, -1, -1) in keep)
        assertTrue(SyncChunkKey(overworld, 1, 1) in keep)
        assertFalse(SyncChunkKey(overworld, 2, 0) in keep)
        assertTrue(DmCheckpointKeepSet.keeps(keep, "region/r.-1.-1.mca", 1023))
        assertFalse(DmCheckpointKeepSet.keeps(keep, "DIM1/region/r.0.0.mca", 0))
    }
}
