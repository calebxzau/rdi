package calebxzau.rdi.mc.server.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheOffer
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkCacheServerSessionTest {
    private val overworld = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")
    private val player = UUID(1, 2)

    private fun session(offersPerTick: Int = ChunkCacheLimits.LEGACY_MAX_OFFERS_PER_TICK) =
        ChunkCacheServerSession(UUID(3, 4), overworld, offersPerTick = offersPerTick)

    private fun offer(id: Long, x: Int, z: Int = 0) = ChunkCacheOffer(id, x, z, ByteArray(20) { id.toByte() })

    @Test
    fun offersBeyondTheTokenBucketAreRetiredUntilItRefills() {
        // 2 per tick, bucket of 2 * OFFER_BURST_TICKS = 8.
        val session = session(offersPerTick = 2)
        var id = 1L
        fun batch(size: Int) = List(size) { offer(id, id.toInt()).also { id++ } }
        // A full bucket absorbs a burst, such as two client ticks landing in one server tick.
        assertTrue(session.admitOffers(batch(8), tick = 5, now = 0) { _, _ -> true }.isEmpty())
        assertEquals(listOf(9L), session.admitOffers(batch(1), tick = 5, now = 0) { _, _ -> true })
        // One tick later only that tick's allowance is back; the batch is split, not refused as a whole.
        assertEquals(listOf(12L), session.admitOffers(batch(3), tick = 6, now = 0) { _, _ -> true })
        // After OFFER_BURST_TICKS idle ticks the bucket is full again.
        assertTrue(session.admitOffers(batch(8), tick = 6 + ChunkCacheLimits.OFFER_BURST_TICKS.toLong(), now = 0) { _, _ -> true }.isEmpty())
        assertEquals(18, session.ledger.offerCount())
        assertEquals(2L, session.metrics.offerRejectedRate)
    }

    @Test
    fun refusedOffersAreCountedAndCountAsAbsentDuringWarmup() {
        // 1 per tick, bucket of OFFER_BURST_TICKS = 4.
        val session = session(offersPerTick = 1)
        val warmup = ChunkCacheWarmup(startedAt = 0, durationMillis = 8_000, immediateRadius = 0)
        session.warmup = warmup
        session.admitOffers(listOf(offer(1, 5)), tick = 0, now = 0) { _, _ -> true }
        // Range and duplicate refusals in one batch; the in-range entry is still admitted.
        assertEquals(listOf(3L, 4L), session.admitOffers(listOf(offer(2, 6), offer(3, 40), offer(4, 5)), tick = 1, now = 0) { x, _ -> x < 10 })
        // The bucket holds 2 tokens now: the first two are admitted, the rest refused for rate.
        val overRate = listOf(offer(5, 7), offer(6, 8), offer(7, 9), offer(8, 11))
        assertEquals(listOf(7L, 8L), session.admitOffers(overRate, tick = 2, now = 0) { _, _ -> true })
        assertEquals(1L, session.metrics.offerRejectedRange)
        assertEquals(1L, session.metrics.offerRejectedDuplicate)
        assertEquals(2L, session.metrics.offerRejectedRate)
        fun release(x: Int) = warmup.classify(ChunkPos.asLong(x, 0), 0, 0, session.ledger.hasOffer(x, 0))
        assertEquals(ChunkCacheWarmup.Release.Absent, release(40))
        assertEquals(ChunkCacheWarmup.Release.Absent, release(11))
        assertEquals(ChunkCacheWarmup.Release.Offered, release(8))
        // The duplicate's position already has an offer, so it is released as offered, not absent.
        assertEquals(ChunkCacheWarmup.Release.Offered, release(5))
    }

    @Test
    fun offersOutsideTheAdmissionRangeAreRetired() {
        val session = session()
        assertEquals(listOf(1L), session.admitOffers(listOf(offer(1, 40)), tick = 0, now = 0) { x, _ -> x < 10 })
        assertEquals(0, session.ledger.offerCount())
    }

    @Test
    fun cancelRetiresOnlyCandidatesWithoutAPendingOutcome() {
        val session = session()
        session.admitOffers(listOf(offer(1, 0), offer(2, 1), offer(3, 2)), tick = 0, now = 0) { _, _ -> true }
        val issued = assertNotNull(session.consumeCandidate(ChunkCacheSentChunks(), player, 1, 0))
        assertTrue(session.ledger.trackReuse(issued, 1_000))
        assertEquals(listOf(1L), session.cancelOffers(listOf(1, 2, 3)) { it == 3L })
    }

    @Test
    fun resultsConfirmOrRepairIssuedReuses() {
        val session = session()
        session.admitOffers(listOf(offer(1, 0), offer(2, 1), offer(3, 2)), tick = 0, now = 0) { _, _ -> true }
        val sent = ChunkCacheSentChunks()
        for (x in 0..2) {
            val token = assertNotNull(session.consumeCandidate(sent, player, x, 0))
            assertTrue(session.ledger.trackReuse(token, 1_000))
        }
        assertEquals(ChunkCacheServerSession.ResultOutcome.Confirmed(1),
            session.resolveResult(1, 0, 0, ByteArray(20) { 1 }, success = true, now = 10))
        assertIs<ChunkCacheServerSession.ResultOutcome.Repair>(session.resolveResult(2, 1, 0, ByteArray(20) { 2 }, success = false, now = 10))
        // A late success still repairs: the client may have dropped its terrain at the deadline.
        assertIs<ChunkCacheServerSession.ResultOutcome.Repair>(session.resolveResult(3, 2, 0, ByteArray(20) { 3 }, success = true, now = 1_000))
        assertEquals(ChunkCacheServerSession.ResultOutcome.Ignored, session.resolveResult(9, 0, 0, ByteArray(20), success = true, now = 10))
        assertEquals(1L, session.metrics.reuseConfirmed)
        assertEquals(1L, session.metrics.clientFailure)
        assertEquals(1L, session.metrics.reuseTimeout)
    }

    @Test
    fun warmingUpSessionsGetTheLargerReuseLimit() {
        val session = session()
        assertEquals(ChunkCacheLimits.MAX_SERVER_REUSE_NANOS_PER_TICK, session.reuseBudgetNanos(now = 0))
        session.warmup = ChunkCacheWarmup(startedAt = 0, durationMillis = 8_000)
        assertEquals(ChunkCacheLimits.WARMUP_MAX_SERVER_REUSE_NANOS_PER_TICK, session.reuseBudgetNanos(now = 100))
        assertEquals(ChunkCacheLimits.MAX_SERVER_REUSE_NANOS_PER_TICK, session.reuseBudgetNanos(now = 8_000))
    }

    @Test
    fun consumeCandidateCountsMissesAndRevisits() {
        val session = session()
        val sent = ChunkCacheSentChunks()
        assertNull(session.consumeCandidate(sent, player, 5, 5))
        assertNull(session.consumeCandidate(sent, player, 5, 5))
        assertEquals(2L, session.metrics.noOfferFullSends)
        assertEquals(1L, session.metrics.noOfferRevisitFullSends)
        assertEquals(2L, session.metrics.normalChunkAttempts)
    }

    @Test
    fun expiryRetiresOldOffersRepairsLateReusesAndDropsOutOfView() {
        val session = session()
        session.admitOffers(listOf(offer(1, 0), offer(2, 1), offer(3, 50)), tick = 0, now = 0) { _, _ -> true }
        val issued = assertNotNull(session.consumeCandidate(ChunkCacheSentChunks(), player, 1, 0))
        assertTrue(session.ledger.trackReuse(issued, 100))
        val early = session.expire(now = 50, inView = { _, _ -> true }, inPrefetch = { x, _ -> x < 10 })
        assertEquals(listOf(3L), early.retire)
        assertTrue(early.repair.isEmpty())
        val late = session.expire(now = ChunkCacheLimits.OFFER_TTL_MILLIS + 1, inView = { _, _ -> true }, inPrefetch = { _, _ -> true })
        assertEquals(listOf(1L), late.retire)
        assertEquals(listOf(2L), late.repair.map { it.id })
        assertEquals(1L, session.metrics.reuseTimeout)
    }

    @Test
    fun issuedReusesOutsideTheViewAreAbandoned() {
        val session = session()
        session.admitOffers(listOf(offer(1, 0)), tick = 0, now = 0) { _, _ -> true }
        val issued = assertNotNull(session.consumeCandidate(ChunkCacheSentChunks(), player, 0, 0))
        assertTrue(session.ledger.trackReuse(issued, 1_000))
        val expiry = session.expire(now = 10, inView = { _, _ -> false }, inPrefetch = { _, _ -> false })
        assertEquals(listOf(1L), expiry.abandon.map { it.id })
        assertEquals(ChunkPos.asLong(0, 0), ChunkPos.asLong(expiry.abandon.single().x, expiry.abandon.single().z))
    }
}
