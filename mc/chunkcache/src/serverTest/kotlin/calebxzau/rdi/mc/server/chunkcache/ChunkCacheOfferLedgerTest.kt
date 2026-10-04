package calebxzau.rdi.mc.server.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheOffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkCacheOfferLedgerTest {
    @Test
    fun `accepts fresh ordered offers and retires duplicate ids or coordinates`(): Unit {
        val ledger = ChunkCacheOfferLedger()
        val accepted = ledger.admit(listOf(offer(1, 0, 0), offer(2, 1, 0)), 10_000) { _, _ -> true }
        assertTrue(accepted.accepted)
        assertEquals(2, ledger.offerCount())

        val duplicateId = ledger.admit(listOf(offer(3, 2, 0), offer(3, 3, 0)), 10_000) { _, _ -> true }
        assertFalse(duplicateId.accepted)
        assertEquals(listOf(3L), duplicateId.retireIds)

        val duplicatePosition = ledger.admit(listOf(offer(4, 2, 0), offer(5, 2, 0)), 10_000) { _, _ -> true }
        assertFalse(duplicatePosition.accepted)
        assertEquals(listOf(4L, 5L), duplicatePosition.retireIds)
        assertEquals(2, ledger.offerCount())
    }

    @Test
    fun `rejects rate sized and capacity batches with terminal ids`(): Unit {
        val ledger = ChunkCacheOfferLedger()
        val oversized = (1L..(ChunkCacheLimits.MAX_OFFER_BATCH + 1)).map { offer(it, it.toInt(), 0) }
        val oversizedResult = ledger.admit(oversized, 10_000) { _, _ -> true }
        assertFalse(oversizedResult.accepted)
        assertEquals(oversized.map { it.id }, oversizedResult.retireIds)

        val boundedLedger = ChunkCacheOfferLedger()
        val first = (1L..64L).map { offer(it, it.toInt(), 0) }
        assertTrue(boundedLedger.admit(first, 10_000) { _, _ -> true }.accepted)
        val second = (65L..128L).map { offer(it, it.toInt(), 1) }
        assertTrue(boundedLedger.admit(second, 10_000) { _, _ -> true }.accepted)
        val overflow = boundedLedger.admit(listOf(offer(129, 129, 1)), 10_000) { _, _ -> true }
        assertFalse(overflow.accepted)
        assertEquals(listOf(129L), overflow.retireIds)
        assertEquals(ChunkCacheLimits.MAX_OFFERS, boundedLedger.offerCount())
    }

    @Test
    fun `cancels a full 128 offer window and keeps cancellation ids distinct`(): Unit {
        val ledger = ChunkCacheOfferLedger()
        val entries = (1L..128L).map { offer(it, it.toInt(), 0) }
        assertTrue(ledger.admit(entries.take(64), 10_000) { _, _ -> true }.accepted)
        assertTrue(ledger.admit(entries.drop(64), 10_000) { _, _ -> true }.accepted)

        val retired = ledger.cancelOfferIds(entries.map { it.id } + entries.first().id)
        assertEquals(entries.map { it.id }, retired)
        assertEquals(0, ledger.offerCount())
    }

    @Test
    fun `expires offers and in flight reuse tokens independently`(): Unit {
        val ledger = ChunkCacheOfferLedger()
        assertTrue(ledger.admit(listOf(offer(1, 0, 0)), 50) { _, _ -> true }.accepted)
        val flight = ledger.consume(0, 0)
        assertNotNull(flight)
        assertTrue(ledger.trackReuse(flight, 80))
        assertTrue(ledger.admit(listOf(offer(2, 1, 0)), 120) { _, _ -> true }.accepted)

        val expired = ledger.expire(80)
        assertEquals(listOf(1L), expired.inFlight.map { it.id })
        assertTrue(expired.offers.isEmpty())
        assertEquals(1, ledger.offerCount())
        assertEquals(listOf(2L), ledger.expire(120).offers.map { it.id })
        assertEquals(0, ledger.offerCount())
        assertEquals(0, ledger.inFlightCount())
    }

    @Test
    fun `only exact issued coordinates and hash can resolve a reuse result`(): Unit {
        val ledger = ChunkCacheOfferLedger()
        val source = offer(1, 8, -3)
        assertTrue(ledger.admit(listOf(source), 100) { _, _ -> true }.accepted)
        val token = assertNotNull(ledger.consume(8, -3))
        assertTrue(ledger.trackReuse(token, 200))

        assertNull(ledger.resolve(1, 9, -3, source.hash, success = false))
        assertNull(ledger.resolve(1, 8, -3, ByteArray(20) { 9 }, success = false))
        assertEquals(1, ledger.inFlightCount())
        val result = assertNotNull(ledger.resolve(1, 8, -3, source.hash, success = false))
        assertFalse(result.success)
        assertEquals(0, ledger.inFlightCount())
        assertNull(ledger.resolve(1, 8, -3, source.hash, success = false))
    }

    @Test
    fun `canceling cannot release a reuse token before its result`(): Unit {
        val ledger = ChunkCacheOfferLedger()
        val entry = offer(1, 8, -3)
        assertTrue(ledger.admit(listOf(entry), 100) { _, _ -> true }.accepted)
        val token = assertNotNull(ledger.consume(8, -3))
        assertTrue(ledger.trackReuse(token, 200))
        ledger.cancelOfferIds(listOf(entry.id))
        assertNotNull(ledger.inFlightToken(entry.id))
        assertNotNull(ledger.resolve(entry.id, entry.x, entry.z, entry.hash, success = true))
    }

    private fun offer(id: Long, x: Int, z: Int) = ChunkCacheOffer(id, x, z, ByteArray(20) { id.toByte() })
}
