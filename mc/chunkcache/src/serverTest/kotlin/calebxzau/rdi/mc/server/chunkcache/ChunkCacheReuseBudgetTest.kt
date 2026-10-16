package calebxzau.rdi.mc.server.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkCacheReuseBudgetTest {
    @Test
    fun spentBudgetRecoversOnTheNextTick() {
        val budget = ChunkCacheReuseBudget()
        assertTrue(budget.available(1, limitNanos = 1_000))
        budget.spend(1_000)
        assertFalse(budget.available(1, limitNanos = 1_000))
        assertEquals(0, budget.allowance(1, limitNanos = 1_000))
        assertTrue(budget.available(2, limitNanos = 1_000))
    }

    @Test
    fun allowanceFollowsTheAverageReuseCost() {
        val budget = ChunkCacheReuseBudget()
        assertEquals(10_000_000 / ChunkCacheReuseBudget.INITIAL_REUSE_NANOS.toInt(), budget.allowance(1, limitNanos = 10_000_000))
        repeat(64) { budget.recordReuse(1_000_000) }
        assertEquals(10, budget.allowance(1, limitNanos = 10_000_000))
        budget.spend(9_999_999)
        // Something always fits while time is left, so a slow average cannot stall warm-up.
        assertEquals(1, budget.allowance(1, limitNanos = 10_000_000))
    }

    @Test
    fun warmupLimitUsesMoreOfTheSameTickBudget() {
        val budget = ChunkCacheReuseBudget()
        budget.available(1)
        budget.spend(ChunkCacheLimits.MAX_SERVER_REUSE_NANOS_PER_TICK)
        assertFalse(budget.available(1))
        assertTrue(budget.available(1, ChunkCacheLimits.WARMUP_MAX_SERVER_REUSE_NANOS_PER_TICK))
        budget.spend(ChunkCacheLimits.WARMUP_MAX_SERVER_REUSE_NANOS_PER_TICK - ChunkCacheLimits.MAX_SERVER_REUSE_NANOS_PER_TICK)
        assertFalse(budget.available(1, ChunkCacheLimits.WARMUP_MAX_SERVER_REUSE_NANOS_PER_TICK))
    }

    @Test
    fun tinyReusesCountAtLeastTheMinimum() {
        val budget = ChunkCacheReuseBudget()
        repeat(64) { budget.recordReuse(0) }
        assertTrue(budget.averageReuseNanos >= ChunkCacheReuseBudget.MIN_REUSE_NANOS)
    }
}
