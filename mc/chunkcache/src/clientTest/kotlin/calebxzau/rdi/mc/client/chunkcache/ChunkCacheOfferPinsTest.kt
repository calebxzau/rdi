package calebxzau.rdi.mc.client.chunkcache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChunkCacheOfferPinsTest {
    @Test
    fun collidingSignedCoordinatesStayPinnedAndCancellationRetainsInsertionOrder(): Unit {
        val pins = ChunkCacheOfferPins<String>(128, 128)
        // These all had identical boxed Long hashes, including the primitive map's zero-key case.
        val positions = (-64L..63L).map { (it and 0xffffffffL) or (it shl 32) }
        val added = positions.mapIndexed { index, position ->
            assertNotNull(pins.add(position, ByteArray(20), "terrain${index}", 1, index.toLong()))
        }
        assertEquals(128, pins.size)
        for (pin in added) {
            assertTrue(pins.contains(pin.position))
            assertSame(pin, pins.get(pin.id))
        }
        val cancelled = pins.cancelWhere { it.id % 2L == 0L }
        assertEquals(added.filter { it.id % 2L == 0L }.map { it.id }, cancelled)
        for (id in cancelled) {
            val pin = assertNotNull(pins.get(id))
            assertTrue(pins.contains(pin.position))
            assertNull(pins.add(pin.position, ByteArray(20), "replacement", 1, 200))
            assertSame(pin, pins.retire(id))
            assertFalse(pins.contains(pin.position))
            assertNull(pins.get(id))
        }
        assertEquals(64, pins.size)
        assertEquals(64, pins.bytes)
        assertEquals(added.filter { it.id % 2L != 0L }.map { it.id }, pins.cancelWhere { true })
        pins.clear()
        positions.forEach { assertFalse(pins.contains(it)) }
        assertNotNull(pins.add(0, ByteArray(20), "next session", 1, 300))
    }

    @Test
    fun cancellationPinsExactSnapshotUntilServerFenceAndBoundsAdmission(): Unit {
        val pins = ChunkCacheOfferPins<ByteArray>(2, 10)
        val firstValue = byteArrayOf(1, 2, 3)
        val first = assertNotNull(pins.add(42, ByteArray(20), firstValue, 6, 1))
        assertNull(pins.add(42, ByteArray(20), byteArrayOf(9), 1, 2))
        assertNull(pins.add(43, ByteArray(20), byteArrayOf(9), 5, 2))
        assertEquals(listOf(first.id), pins.cancelWhere { true })
        assertTrue(pins.get(first.id)!!.cancelling)
        assertSame(firstValue, pins.get(first.id)!!.value)
        assertEquals(6, pins.bytes)
        assertTrue(pins.cancelWhere { true }.isEmpty())
        pins.retire(first.id)
        assertEquals(0, pins.bytes)
        assertNull(pins.get(first.id))
        val replacement = assertNotNull(pins.add(42, ByteArray(20), byteArrayOf(4), 6, 3))
        assertTrue(replacement.id > first.id)
    }

    @Test
    fun storesIndependentHashAndClearReleasesPins(): Unit {
        val pins = ChunkCacheOfferPins<String>(1, 10)
        val hash = ByteArray(20) { 7 }
        val pin = assertNotNull(pins.add(1, hash, "snapshot", 2, 1))
        hash.fill(0)
        assertEquals(7, pin.hash[0].toInt())
        assertNull(pins.add(2, hash, "second", 2, 1))
        pins.clear()
        assertEquals(0, pins.size)
        assertEquals(0, pins.bytes)
    }
}
