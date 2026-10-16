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

        // Only the second offer of a position is refused; the first stands.
        val duplicatePosition = ledger.admit(listOf(offer(4, 2, 0), offer(5, 2, 0)), 10_000) { _, _ -> true }
        assertFalse(duplicatePosition.accepted)
        assertEquals(listOf(5L), duplicatePosition.retireIds)
        assertEquals(3, ledger.offerCount())
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
        assertEquals(ChunkCacheLimits.LEGACY_MAX_OFFERS, boundedLedger.offerCount())
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

    @Test
    fun `negotiated capacity admits more than the legacy window`(): Unit {
        val ledger = ChunkCacheOfferLedger(ChunkCacheLimits.MAX_OFFERS)
        var id = 1L
        repeat(ChunkCacheLimits.MAX_OFFERS / ChunkCacheLimits.MAX_OFFER_BATCH) { batch ->
            val entries = (0 until ChunkCacheLimits.MAX_OFFER_BATCH).map { offer(id++, it, batch) }
            assertTrue(ledger.admit(entries, 10_000) { _, _ -> true }.accepted)
        }
        assertEquals(ChunkCacheLimits.MAX_OFFERS, ledger.offerCount())
        assertFalse(ledger.admit(listOf(offer(id, 0, 9_999)), 10_000) { _, _ -> true }.accepted)
    }

    @Test
    fun `position index follows every removal path`(): Unit {
        val ledger = ChunkCacheOfferLedger()
        assertTrue(ledger.admit(listOf(offer(1, -5, 7), offer(2, 6, -8), offer(3, 0, 0), offer(4, 9, 9)), 100) { _, _ -> true }.accepted)
        assertTrue(ledger.hasOffer(-5, 7))
        assertFalse(ledger.hasOffer(7, -5))

        ledger.cancelOfferIds(listOf(1))
        assertFalse(ledger.hasOffer(-5, 7))
        assertNull(ledger.consume(-5, 7))

        assertEquals(listOf(2L), ledger.retireOffersOutside { x, _ -> x != 6 })
        assertFalse(ledger.hasOffer(6, -8))

        val token = assertNotNull(ledger.consume(0, 0))
        assertFalse(ledger.hasOffer(0, 0))
        assertTrue(ledger.trackReuse(token, 50))
        // A position with an in-flight reuse cannot be offered again until it resolves.
        assertFalse(ledger.admit(listOf(offer(5, 0, 0)), 100) { _, _ -> true }.accepted)
        assertEquals(listOf(token.id), ledger.expire(60).inFlight.map { it.id })
        assertTrue(ledger.admit(listOf(offer(6, 0, 0)), 500) { _, _ -> true }.accepted)
        assertTrue(ledger.hasOffer(0, 0))

        ledger.rejectIds(listOf(4))
        assertFalse(ledger.hasOffer(9, 9))
        assertNotNull(ledger.retireOffer(6))
        assertFalse(ledger.hasOffer(0, 0))
    }

    @Test
    fun `one refused entry does not cost the rest of its batch`(): Unit {
        val ledger = ChunkCacheOfferLedger(capacity = 3)
        assertTrue(ledger.admit(listOf(offer(1, 0, 0)), 10_000) { _, _ -> true }.accepted)
        val admission = ledger.admit(
            listOf(offer(2, 1, 0), offer(3, 50, 0), offer(4, 0, 0), offer(5, 2, 0), offer(6, 3, 0)),
            10_000,
        ) { x, _ -> x < 10 }
        assertEquals(
            listOf(
                3L to ChunkCacheOfferLedger.RejectReason.Range,
                4L to ChunkCacheOfferLedger.RejectReason.Duplicate,
                6L to ChunkCacheOfferLedger.RejectReason.Capacity,
            ),
            admission.rejected.map { it.id to it.reason },
        )
        assertEquals(listOf(3L, 4L, 6L), admission.retireIds)
        assertTrue(ledger.hasOffer(1, 0))
        assertTrue(ledger.hasOffer(2, 0))
        assertEquals(3, ledger.offerCount())
    }

    @Test
    fun `repeated or stale ids are invalid and drop a matching existing offer`(): Unit {
        val ledger = ChunkCacheOfferLedger()
        assertTrue(ledger.admit(listOf(offer(5, 0, 0)), 10_000) { _, _ -> true }.accepted)
        val repeated = ledger.admit(listOf(offer(7, 1, 0), offer(7, 2, 0), offer(8, 3, 0)), 10_000) { _, _ -> true }
        assertEquals(listOf(7L), repeated.retireIds)
        assertTrue(repeated.rejected.all { it.reason == ChunkCacheOfferLedger.RejectReason.Invalid })
        assertTrue(ledger.hasOffer(3, 0))
        // Id 5 is no longer fresh; the client is told to retire it, so its standing offer goes too.
        val stale = ledger.admit(listOf(offer(5, 4, 0)), 10_000) { _, _ -> true }
        assertEquals(listOf(5L), stale.retireIds)
        assertFalse(ledger.hasOffer(0, 0))
    }

    private fun offer(id: Long, x: Int, z: Int) = ChunkCacheOffer(id, x, z, ByteArray(20) { id.toByte() })
}
