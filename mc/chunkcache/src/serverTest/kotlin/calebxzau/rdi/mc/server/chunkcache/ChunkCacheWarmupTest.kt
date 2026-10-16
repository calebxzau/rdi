package calebxzau.rdi.mc.server.chunkcache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkCacheWarmupTest {
    private fun pos(x: Int, z: Int): Long = (x.toLong() and 0xffffffffL) or ((z.toLong() and 0xffffffffL) shl 32)

    private fun warmup() = ChunkCacheWarmup(startedAt = 1_000, durationMillis = 3_000, immediateRadius = 1)

    @Test
    fun `chunks next to the player are sent at once and others wait for a decision`(): Unit {
        val warmup = warmup()
        assertEquals(ChunkCacheWarmup.Release.Immediate, warmup.classify(pos(11, -21), 10, -20, hasOffer = false))
        assertEquals(ChunkCacheWarmup.Release.Held, warmup.classify(pos(12, -20), 10, -20, hasOffer = false))
        assertEquals(ChunkCacheWarmup.Release.Offered, warmup.classify(pos(12, -20), 10, -20, hasOffer = true))
        warmup.markAbsent(listOf(pos(12, -20)))
        assertEquals(ChunkCacheWarmup.Release.Absent, warmup.classify(pos(12, -20), 10, -20, hasOffer = false))
    }

    @Test
    fun `warm-up ends at its deadline`(): Unit {
        val warmup = warmup()
        assertTrue(warmup.active(1_000))
        assertTrue(warmup.active(3_999))
        assertFalse(warmup.active(4_000))
    }

    @Test
    fun `selection sends releasable chunks nearest first within the quota`(): Unit {
        val warmup = warmup()
        val offered = setOf(pos(5, 0), pos(2, 0))
        warmup.markAbsent(listOf(pos(3, 0)))
        val pending = listOf(pos(5, 0), pos(4, 0), pos(3, 0), pos(2, 0), pos(1, 0), pos(0, 0))

        val selected = warmup.select(pending, quota = 3, centerX = 0, centerZ = 0, offerAllowance = 10, hasOffer = { it in offered }, ready = { true })

        // (4, 0) has no decision yet; (5, 0) is offered but the quota is used up by nearer chunks.
        assertEquals(listOf(pos(0, 0), pos(1, 0), pos(2, 0)), selected)
        assertEquals(listOf(pos(3, 0), pos(5, 0)),
            warmup.select(pending - selected.toSet(), quota = 9, centerX = 0, centerZ = 0, offerAllowance = 10, hasOffer = { it in offered }, ready = { true }))
        val summary = warmup.summary(now = 2_500, heldAtEnd = 1)
        assertEquals(2, summary.immediate)
        assertEquals(2, summary.offered)
        assertEquals(1, summary.absent)
        assertEquals(1, summary.heldAtEnd)
        assertEquals(1_500, summary.durationMillis)
    }

    @Test
    fun `offered chunks beyond the reuse budget wait for a later tick`(): Unit {
        val warmup = warmup()
        val offered = setOf(pos(3, 0), pos(4, 0), pos(5, 0))
        warmup.markAbsent(listOf(pos(6, 0)))
        val pending = listOf(pos(3, 0), pos(4, 0), pos(5, 0), pos(6, 0))

        val selected = warmup.select(pending, quota = 10, centerX = 0, centerZ = 0, offerAllowance = 1, hasOffer = { it in offered }, ready = { true })

        assertEquals(listOf(pos(3, 0), pos(6, 0)), selected)
        assertEquals(2, warmup.summary(0, 0).deferredForBudget)
    }

    @Test
    fun `chunks the server cannot send yet stay pending without using the quota`(): Unit {
        val warmup = warmup()
        val pending = listOf(pos(0, 0), pos(1, 0), pos(0, 1))

        val selected = warmup.select(pending, quota = 2, centerX = 0, centerZ = 0, offerAllowance = 0, hasOffer = { false }, ready = { it != pos(0, 0) })

        assertEquals(listOf(pos(1, 0), pos(0, 1)), selected)
    }

    @Test
    fun `absent reports are bounded`(): Unit {
        val warmup = warmup()
        warmup.markAbsent((0 until ChunkCacheWarmup.MAX_TRACKED_ABSENT + 10).map { pos(it + 10, 0) })
        assertEquals(ChunkCacheWarmup.Release.Absent, warmup.classify(pos(10, 0), 0, 0, hasOffer = false))
        assertEquals(ChunkCacheWarmup.Release.Held, warmup.classify(pos(ChunkCacheWarmup.MAX_TRACKED_ABSENT + 15, 0), 0, 0, hasOffer = false))
    }

    @Test
    fun `negative coordinates keep their position`(): Unit {
        val position = pos(-30_000_000, 29_999_999)
        assertEquals(-30_000_000, ChunkCacheWarmup.chunkX(position))
        assertEquals(29_999_999, ChunkCacheWarmup.chunkZ(position))
    }
}
