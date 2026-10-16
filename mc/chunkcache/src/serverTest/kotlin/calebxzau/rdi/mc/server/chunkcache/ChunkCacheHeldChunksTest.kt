package calebxzau.rdi.mc.server.chunkcache

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkCacheHeldChunksTest {
    @Test
    fun forgetReportsOnlyHeldChunks() {
        val held = ChunkCacheHeldChunks()
        held.hold(1)
        held.hold(1)
        assertEquals(1, held.size)
        assertTrue(held.forget(1))
        assertFalse(held.forget(1))
        assertTrue(held.isEmpty())
    }

    @Test
    fun pruneDropsChunksThatCannotBeSentAndKeepsOrder() {
        val held = ChunkCacheHeldChunks()
        listOf(5L, 3L, 8L, 4L).forEach(held::hold)
        val remaining = held.prune { it % 2 == 0L }
        assertContentEquals(longArrayOf(8, 4), remaining.toLongArray())
        assertEquals(2, held.size)
    }

    @Test
    fun releaseAndTakeAllEmptyTheQueue() {
        val held = ChunkCacheHeldChunks()
        listOf(7L, 2L, 9L).forEach(held::hold)
        assertTrue(held.release(2))
        assertFalse(held.release(2))
        assertContentEquals(longArrayOf(7, 9), held.takeAll())
        assertTrue(held.isEmpty())
    }
}
