package calebxzau.rdi.mc.server.chunkcache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkCacheRepairsTest {
    @Test
    fun repairDoesNotReleaseItsFenceBeforeMatchingFullTransmission(): Unit {
        val repairs = ChunkCacheRepairs()
        val token = token(7, 8, -3)
        repairs.add(token)
        assertTrue(repairs.contains(7)) // A crossing cancellation must not acknowledge this result fence.
        assertNull(repairs.completeFull(8, -2))
        assertTrue(repairs.contains(7))
        assertEquals(token, assertNotNull(repairs.completeFull(8, -3)))
        assertFalse(repairs.contains(7))
        assertNull(repairs.completeFull(8, -3))
    }

    @Test
    fun leavingViewIdentifiesAbandonmentWithoutReleasingBeforeUnloadAndFence(): Unit {
        val repairs = ChunkCacheRepairs()
        repairs.add(token(1, 0, 0))
        repairs.add(token(2, 9, 9))
        val abandoned = repairs.outside { x, z -> x == 0 && z == 0 }
        assertEquals(listOf(2L), abandoned.map { it.id })
        assertTrue(repairs.contains(2)) // Service sends Forget and Retire when processing this token.
        abandoned.forEach { repairs.remove(it.id) }
        assertFalse(repairs.contains(2))
        assertTrue(repairs.contains(1))
        assertTrue(repairs.outside { x, z -> x == 0 && z == 0 }.isEmpty())
    }

    private fun token(id: Long, x: Int, z: Int) = ChunkCacheOfferLedger.Token(id, x, z, ByteArray(20), 10_000)
}
