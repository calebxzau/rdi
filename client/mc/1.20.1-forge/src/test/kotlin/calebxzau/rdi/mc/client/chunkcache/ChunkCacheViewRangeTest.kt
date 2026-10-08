package calebxzau.rdi.mc.client.chunkcache

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkCacheViewRangeTest {
    @Test
    fun `candidates cover the ring a 1_20_1 server sends next`() {
        for (viewDistance in 2..31) {
            // The server tracks the player's view distance; one step later the next ring enters it.
            for (dx in -40..40) for (dz in -40..40) {
                if (ChunkCacheViewRange.isChunkInRange(dx, dz, 1, 0, viewDistance)) {
                    assertTrue(ChunkCacheViewRange.admits(dx, dz, 0, 0, viewDistance), "view=$viewDistance ($dx, $dz)")
                }
            }
        }
    }

    @Test
    fun `scan square contains every admitted chunk`() {
        for (viewDistance in 2..31) {
            val radius = ChunkCacheViewRange.scanRadius(viewDistance)
            var edgeReached = false
            for (dx in -40..40) for (dz in -40..40) {
                if (ChunkCacheViewRange.admits(dx, dz, 0, 0, viewDistance)) {
                    val distance = max(abs(dx), abs(dz))
                    assertTrue(distance <= radius, "view=$viewDistance ($dx, $dz) outside scan radius $radius")
                    if (distance == radius) edgeReached = true
                }
            }
            assertTrue(edgeReached, "view=$viewDistance scan radius $radius is wider than needed")
        }
    }

    @Test
    fun `range copy keeps the vanilla 1_20_1 shape`() {
        // Values worked out from ChunkMap.isChunkInRange in Forge 47.4.20 sources.
        assertTrue(ChunkCacheViewRange.isChunkInRange(11, 0, 0, 0, 10))
        assertFalse(ChunkCacheViewRange.isChunkInRange(12, 0, 0, 0, 10))
        assertTrue(ChunkCacheViewRange.isChunkInRange(8, 8, 0, 0, 10))
        assertFalse(ChunkCacheViewRange.isChunkInRange(9, 9, 0, 0, 10))
        assertTrue(ChunkCacheViewRange.isChunkInRange(-11, 1, 0, 0, 10))
        assertTrue(ChunkCacheViewRange.isChunkInRange(0, 0, 0, 0, 1))
    }

    @Test
    fun `admission matches the server prefetch margin`() {
        assertTrue(ChunkCacheViewRange.admits(13, 0, 0, 0, viewDistance = 10))
        assertFalse(ChunkCacheViewRange.admits(14, 0, 0, 0, viewDistance = 10))
        // Square corners the old scan prepared are rejected by the server.
        assertFalse(ChunkCacheViewRange.admits(12, 12, 0, 0, viewDistance = 10))
    }

    @Test
    fun `view distance follows the server and stays within scan bounds`() {
        assertEquals(2, ChunkCacheViewRange.viewDistance(0))
        assertEquals(10, ChunkCacheViewRange.viewDistance(10))
        assertEquals(32, ChunkCacheViewRange.viewDistance(64))
        assertEquals(34, ChunkCacheViewRange.scanRadius(32))
    }
}
