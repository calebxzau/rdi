package calebxzau.rdi.mc.server.chunkcache

/** Main-thread-owned counters for one cache session and one 1,200-tick log window. */
internal class ChunkCacheServerMetrics {
    var normalChunkAttempts = 0L
        private set
    var noOfferFullSends = 0L
        private set
    var candidateFallbackFullSends = 0L
        private set
    var forcedRepairFullSends = 0L
        private set
    var mismatches = 0L
        private set
    var reuseSent = 0L
        private set
    var reuseConfirmed = 0L
        private set
    var clientFailure = 0L
        private set
    var reuseTimeout = 0L
        private set
    var repairQueued = 0L
        private set
    var repairCompleted = 0L
        private set
    var repairAbandoned = 0L
        private set

    var rawSectionPayloadBytes = 0L
        private set
    var reuseMetadataBytes = 0L
        private set

    var sectionCopyDecodeNanos = 0L
        private set
    var sectionCopyDecodeMaxNanos = 0L
        private set
    var semanticHashNanos = 0L
        private set
    var semanticHashMaxNanos = 0L
        private set
    var metadataEncodeNanos = 0L
        private set
    var metadataEncodeMaxNanos = 0L
        private set
    var replacementNanos = 0L
        private set
    var replacementMaxNanos = 0L
        private set

    var replacementTick = Long.MIN_VALUE
        private set
    var replacementNanosCurrentTick = 0L
        private set
    var replacementNanosMaxTick = 0L
        private set

    fun recordNormalChunkAttempt() { normalChunkAttempts++ }
    fun recordNoOfferFullSend() { noOfferFullSends++ }
    fun recordCandidateFallbackFullSend() { candidateFallbackFullSends++ }
    fun recordForcedRepairFullSend() { forcedRepairFullSends++ }
    fun recordMismatch() { mismatches++ }
    fun recordReuseSent() { reuseSent++ }
    fun recordReuseConfirmed() { reuseConfirmed++ }
    fun recordClientFailure() { clientFailure++ }
    fun recordReuseTimeout() { reuseTimeout++ }
    fun recordRepairQueued() { repairQueued++ }
    fun recordRepairCompleted() { repairCompleted++ }
    fun recordRepairAbandoned() { repairAbandoned++ }
    fun recordRawSectionPayloadBytes(bytes: Int) { rawSectionPayloadBytes += bytes }
    fun recordReuseMetadataBytes(bytes: Int) { reuseMetadataBytes += bytes }

    fun recordSectionCopyDecode(nanos: Long) {
        sectionCopyDecodeNanos += nanos
        sectionCopyDecodeMaxNanos = maxOf(sectionCopyDecodeMaxNanos, nanos)
    }

    fun recordSemanticHash(nanos: Long) {
        semanticHashNanos += nanos
        semanticHashMaxNanos = maxOf(semanticHashMaxNanos, nanos)
    }

    fun recordMetadataEncode(nanos: Long) {
        metadataEncodeNanos += nanos
        metadataEncodeMaxNanos = maxOf(metadataEncodeMaxNanos, nanos)
    }

    /** Times chunk packets and their bundles, including no-offer lookup and exception fallback. */
    fun recordReplacement(tick: Long, nanos: Long) {
        advanceReplacementTick(tick)
        replacementNanos += nanos
        replacementMaxNanos = maxOf(replacementMaxNanos, nanos)
        replacementNanosCurrentTick += nanos
        replacementNanosMaxTick = maxOf(replacementNanosMaxTick, replacementNanosCurrentTick)
    }

    fun advanceReplacementTick(tick: Long) {
        if (replacementTick != tick) {
            replacementTick = tick
            replacementNanosCurrentTick = 0
        }
    }

    fun hasWindowActivity(): Boolean =
        normalChunkAttempts + noOfferFullSends + candidateFallbackFullSends + forcedRepairFullSends + mismatches + reuseSent +
            reuseConfirmed + clientFailure + reuseTimeout + repairQueued + repairCompleted + repairAbandoned > 0L

    fun resetWindow() {
        normalChunkAttempts = 0
        noOfferFullSends = 0
        candidateFallbackFullSends = 0
        forcedRepairFullSends = 0
        mismatches = 0
        reuseSent = 0
        reuseConfirmed = 0
        clientFailure = 0
        reuseTimeout = 0
        repairQueued = 0
        repairCompleted = 0
        repairAbandoned = 0
        rawSectionPayloadBytes = 0
        reuseMetadataBytes = 0
        sectionCopyDecodeNanos = 0
        sectionCopyDecodeMaxNanos = 0
        semanticHashNanos = 0
        semanticHashMaxNanos = 0
        metadataEncodeNanos = 0
        metadataEncodeMaxNanos = 0
        replacementNanos = 0
        replacementMaxNanos = 0
        replacementNanosCurrentTick = 0
        replacementNanosMaxTick = 0
    }
}
