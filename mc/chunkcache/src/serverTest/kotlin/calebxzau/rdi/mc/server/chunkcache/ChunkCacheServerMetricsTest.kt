package calebxzau.rdi.mc.server.chunkcache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkCacheServerMetricsTest {
    @Test
    fun recordsOutcomeCountersAndResetsTheWindow() {
        val metrics = ChunkCacheServerMetrics()

        metrics.recordNormalChunkAttempt()
        metrics.recordNoOfferFullSend()
        metrics.recordCandidateFallbackFullSend()
        metrics.recordForcedRepairFullSend()
        metrics.recordMismatch()
        metrics.recordReuseSent()
        metrics.recordReuseConfirmed()
        metrics.recordClientFailure()
        metrics.recordReuseTimeout()
        metrics.recordRepairQueued()
        metrics.recordRepairCompleted()
        metrics.recordRepairAbandoned()
        metrics.recordRawSectionPayloadBytes(32)
        metrics.recordReuseMetadataBytes(12)

        assertTrue(metrics.hasWindowActivity())
        assertEquals(1L, metrics.normalChunkAttempts)
        assertEquals(1L, metrics.noOfferFullSends)
        assertEquals(1L, metrics.candidateFallbackFullSends)
        assertEquals(1L, metrics.reuseSent)
        assertEquals(1L, metrics.reuseConfirmed)
        assertEquals(32L, metrics.rawSectionPayloadBytes)
        assertEquals(12L, metrics.reuseMetadataBytes)

        metrics.resetWindow()

        assertFalse(metrics.hasWindowActivity())
        assertEquals(0L, metrics.normalChunkAttempts)
        assertEquals(0L, metrics.reuseSent)
        assertEquals(0L, metrics.reuseConfirmed)
        assertEquals(0L, metrics.rawSectionPayloadBytes)
    }

    @Test
    fun replacementTimingTracksCallMaximumAndPerTickTotals() {
        val metrics = ChunkCacheServerMetrics()

        metrics.recordReplacement(tick = 10, nanos = 4)
        metrics.recordReplacement(tick = 10, nanos = 7)
        assertEquals(11L, metrics.replacementNanosCurrentTick)
        assertEquals(11L, metrics.replacementNanosMaxTick)
        assertEquals(7L, metrics.replacementMaxNanos)

        metrics.advanceReplacementTick(11)
        assertEquals(0L, metrics.replacementNanosCurrentTick)
        metrics.recordReplacement(tick = 11, nanos = 3)
        assertEquals(3L, metrics.replacementNanosCurrentTick)
        assertEquals(11L, metrics.replacementNanosMaxTick)
        assertEquals(14L, metrics.replacementNanos)
    }
}
