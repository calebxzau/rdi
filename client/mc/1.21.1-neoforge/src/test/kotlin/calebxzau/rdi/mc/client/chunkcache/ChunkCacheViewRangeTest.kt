package calebxzau.rdi.mc.client.chunkcache

import net.minecraft.server.level.ChunkTrackingView
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkCacheViewRangeTest {
    @Test
    fun `candidates cover the chunks a 1_21 server sends after one step`() {
        for (viewDistance in 2..31) {
            // The server sends what its tracking view contains, including the outer border chunks.
            for (dx in -40..40) for (dz in -40..40) {
                if (ChunkTrackingView.isWithinDistance(1, 0, viewDistance, dx, dz, true)) {
                    assertTrue(ChunkCacheViewRange.admits(dx, dz, 0, 0, viewDistance), "view=${viewDistance} (${dx}, ${dz})")
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
                    assertTrue(distance <= radius, "view=${viewDistance} (${dx}, ${dz}) outside scan radius ${radius}")
                    if (distance == radius) edgeReached = true
                }
            }
            assertTrue(edgeReached, "view=${viewDistance} scan radius ${radius} is wider than needed")
        }
    }

    @Test
    fun `admission matches the server prefetch margin`() {
        assertTrue(ChunkCacheViewRange.admits(13, 0, 0, 0, viewDistance = 10))
        assertFalse(ChunkCacheViewRange.admits(14, 0, 0, 0, viewDistance = 10))
        // Square corners the old scan prepared are rejected by the server.
        assertFalse(ChunkCacheViewRange.admits(12, 12, 0, 0, viewDistance = 10))
    }

    @Test
    fun `view distance stays within scan bounds`() {
        assertEquals(2, ChunkCacheViewRange.viewDistance(0))
        assertEquals(10, ChunkCacheViewRange.viewDistance(10))
        assertEquals(32, ChunkCacheViewRange.viewDistance(64))
        assertEquals(34, ChunkCacheViewRange.scanRadius(32))
    }
}
