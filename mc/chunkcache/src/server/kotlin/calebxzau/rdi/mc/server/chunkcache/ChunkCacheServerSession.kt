package calebxzau.rdi.mc.server.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheOffer
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import org.slf4j.Logger
import java.util.UUID

/**
 * One player's cache session and the protocol steps that do not depend on how a Minecraft version sends chunks.
 * Each version's service subclasses it with what its send path needs and turns the returned decisions into
 * messages. Owned by the server thread.
 */
internal open class ChunkCacheServerSession(
    val epoch: UUID,
    val dimension: ResourceLocation,
    /** Candidates this client may hold; larger only when it negotiated warm-up. */
    val capacity: Int = ChunkCacheLimits.LEGACY_MAX_OFFERS,
    val offersPerTick: Int = ChunkCacheLimits.LEGACY_MAX_OFFERS_PER_TICK,
    var warmup: ChunkCacheWarmup? = null,
    /** Server tick at which the current metrics window started. */
    var metricTick: Long = 0,
) {
    val ledger = ChunkCacheOfferLedger(capacity)
    val metrics = ChunkCacheServerMetrics()
    /** The client reported that its cache paused; logged once per session. */
    var clientPaused = false
    /** Token bucket for offers: [offersPerTick] per server tick, up to [ChunkCacheLimits.OFFER_BURST_TICKS] ticks' worth. */
    private val maxOfferTokens = offersPerTick.toLong() * ChunkCacheLimits.OFFER_BURST_TICKS
    private var offerTokens = maxOfferTokens
    private var offerTokensTick = Long.MIN_VALUE

    sealed interface ResultOutcome {
        /** Not a reuse this session issued; nothing to do. */
        object Ignored : ResultOutcome
        /** Retire [id]: the client applied the reuse. */
        data class Confirmed(val id: Long) : ResultOutcome
        /** The client has no terrain for [token]'s chunk and waits for the full chunk. */
        data class Repair(val token: ChunkCacheOfferLedger.Token) : ResultOutcome
    }

    /** What [expire] decided: ids to retire, reuses to repair with a full chunk, and repairs to abandon. */
    data class Expiry(
        val retire: List<Long>,
        val repair: List<ChunkCacheOfferLedger.Token>,
        val abandon: List<ChunkCacheOfferLedger.Token>,
    )

    /**
     * Admits a client's offers; returns the ids to retire, empty when all were accepted. [allowed] is the version's
     * admission range. While warming up, a refused chunk counts as absent: the client will not offer it again before
     * its retry delay, so holding it would only make it wait for the end of the warm-up.
     */
    fun admitOffers(entries: List<ChunkCacheOffer>, tick: Long, now: Long, allowed: (Int, Int) -> Boolean): List<Long> {
        if (entries.isEmpty()) return emptyList()
        refillOfferTokens(tick)
        val withinRate = minOf(entries.size.toLong(), offerTokens).toInt()
        offerTokens -= withinRate
        val retire = ArrayList<Long>()
        // Admit first: refusing the later entries raises the last seen id, which would make earlier ones stale.
        if (withinRate > 0) {
            val admission = ledger.admit(entries.subList(0, withinRate), now + ChunkCacheLimits.OFFER_TTL_MILLIS, allowed)
            admission.rejected.forEach { metrics.recordOfferRejected(it.reason) }
            // A duplicate position already has an offer or a reuse in flight, so its chunk is not waiting for this one.
            warmup?.markAbsent(admission.rejected.filter { it.reason != ChunkCacheOfferLedger.RejectReason.Duplicate }
                .map { ChunkPos.asLong(it.x, it.z) })
            retire += admission.retireIds
        }
        if (withinRate < entries.size) {
            val overRate = entries.subList(withinRate, entries.size)
            ledger.rejectIds(overRate.map { it.id })
            metrics.recordOfferRejected(ChunkCacheOfferLedger.RejectReason.Rate, overRate.size)
            warmup?.markAbsent(overRate.map { ChunkPos.asLong(it.x, it.z) })
            retire += overRate.map { it.id }
        }
        return retire.distinct()
    }

    private fun refillOfferTokens(tick: Long) {
        if (offerTokensTick == Long.MIN_VALUE || tick <= offerTokensTick) {
            if (offerTokensTick == Long.MIN_VALUE) offerTokensTick = tick
            return
        }
        val elapsed = tick - offerTokensTick
        offerTokensTick = tick
        offerTokens = if (elapsed >= ChunkCacheLimits.OFFER_BURST_TICKS) maxOfferTokens
        else minOf(maxOfferTokens, offerTokens + elapsed * offersPerTick)
    }

    /**
     * Cancels offers and returns the ids to retire now. Issued reuses, and ids the version still owes a full chunk
     * for ([awaitingOutcome]), are retired once their outcome is known.
     */
    fun cancelOffers(ids: List<Long>, awaitingOutcome: (Long) -> Boolean = { false }): List<Long> {
        if (ids.size > ChunkCacheLimits.MAX_IDS_PER_MESSAGE) return emptyList()
        ledger.cancelOfferIds(ids)
        // Retire is an explicit fence: clients keep each pinned snapshot until this acknowledgement.
        val waiting = ids.filter { awaitingOutcome(it) || ledger.inFlightToken(it) != null }.toSet()
        return (ids - waiting).distinct()
    }

    fun resolveResult(id: Long, x: Int, z: Int, hash: ByteArray, success: Boolean, now: Long): ResultOutcome {
        val issued = ledger.resolve(id, x, z, hash, success) ?: return ResultOutcome.Ignored
        if (issued.token.expiresAt <= now) {
            metrics.recordReuseTimeout()
            if (!success) metrics.recordClientFailure()
            return ResultOutcome.Repair(issued.token)
        }
        if (success) {
            metrics.recordReuseConfirmed()
            return ResultOutcome.Confirmed(id)
        }
        metrics.recordClientFailure()
        return ResultOutcome.Repair(issued.token)
    }

    /** The candidate a chunk send may reuse, or null after recording why there is none. */
    fun consumeCandidate(sentChunks: ChunkCacheSentChunks, player: UUID, x: Int, z: Int): ChunkCacheOfferLedger.Token? {
        metrics.recordNormalChunkAttempt()
        val revisit = sentChunks.markSent(player, dimension, ChunkPos.asLong(x, z))
        return ledger.consume(x, z) ?: run {
            metrics.recordNoOfferFullSend()
            if (revisit) metrics.recordNoOfferRevisitFullSend()
            null
        }
    }

    /** Ends timed-out offers and reuses; [inView] is the version's send range, [inPrefetch] its admission range. */
    fun expire(now: Long, inView: (Int, Int) -> Boolean, inPrefetch: (Int, Int) -> Boolean): Expiry {
        val expired = ledger.expire(now)
        val retire = expired.offers.mapTo(ArrayList()) { it.id }
        val repair = expired.inFlight.filter { it.expiresAt <= now }
        repair.forEach { metrics.recordReuseTimeout() }
        val abandon = ledger.retireInFlightOutside(inView)
        retire += ledger.retireOffersOutside(inPrefetch)
        return Expiry(retire, repair, abandon)
    }

    /** This session's limit of the server-wide reuse budget per tick: larger while it warms up. */
    fun reuseBudgetNanos(now: Long): Long =
        if (warmup?.active(now) == true) ChunkCacheLimits.WARMUP_MAX_SERVER_REUSE_NANOS_PER_TICK
        else ChunkCacheLimits.MAX_SERVER_REUSE_NANOS_PER_TICK

    /** Ends warm-up with one log line; [heldAtEnd] chunks are then sent the ordinary way. */
    fun finishWarmup(logger: Logger, playerName: String, heldAtEnd: Int, now: Long, averageReuseNanos: Long) {
        val ended = warmup ?: return
        warmup = null
        val summary = ended.summary(now, heldAtEnd)
        logger.info(
            "Chunk cache warm-up player={} ms={} immediate={} offered={} absent={} heldAtEnd={} budgetDeferrals={} avgReuseMicros={}",
            playerName, summary.durationMillis, summary.immediate, summary.offered, summary.absent,
            summary.heldAtEnd, summary.deferredForBudget, averageReuseNanos / 1_000,
        )
    }

    /** Logs and starts a new metrics window once a minute (1,200 ticks) has passed. */
    fun logMinuteIfDue(logger: Logger, playerName: String, tick: Long) {
        if (tick - metricTick < 1_200L) return
        logMetricsWindow(logger, "minute", playerName)
        metricTick = tick
    }

    /** Logs and clears the current window; [window] is `minute` or why the session ended before its minute closed. */
    fun logMetricsWindow(logger: Logger, window: String, playerName: String) {
        val metrics = metrics
        if (metrics.hasWindowActivity()) {
            logger.info(
                "Chunk cache {} player={} normalAttempts={} noOfferFullSends={} noOfferRevisitFullSends={} candidateFallbackFullSends={} forcedRepairFullSends={} mismatches={} reuseSent={} reuseConfirmed={} clientFailure={} reuseTimeout={} repairQueued={} repairCompleted={} repairAbandonedEvents={} budgetFallbackFullSends={} offerRejectedRange={} offerRejectedCapacity={} offerRejectedDuplicate={} offerRejectedInvalid={} offerRejectedRate={} sectionHashHits={} sectionHashMisses={} sectionHashStale={} rawSectionPayloadBytes={} reuseMetadataBytes={} sectionCopyDecodeNanosTotal={} sectionCopyDecodeNanosMax={} semanticHashNanosTotal={} semanticHashNanosMax={} metadataEncodeNanosTotal={} metadataEncodeNanosMax={} replacementNanosTotal={} replacementNanosMax={} replacementNanosCurrentTick={} replacementNanosMaxTick={} replacementTickBoundary=perPlayerSessionServerTick",
                window,
                playerName,
                metrics.normalChunkAttempts,
                metrics.noOfferFullSends,
                metrics.noOfferRevisitFullSends,
                metrics.candidateFallbackFullSends,
                metrics.forcedRepairFullSends,
                metrics.mismatches,
                metrics.reuseSent,
                metrics.reuseConfirmed,
                metrics.clientFailure,
                metrics.reuseTimeout,
                metrics.repairQueued,
                metrics.repairCompleted,
                metrics.repairAbandoned,
                metrics.budgetFallbackFullSends,
                metrics.offerRejectedRange,
                metrics.offerRejectedCapacity,
                metrics.offerRejectedDuplicate,
                metrics.offerRejectedInvalid,
                metrics.offerRejectedRate,
                metrics.sectionHashHits,
                metrics.sectionHashMisses,
                metrics.sectionHashStale,
                metrics.rawSectionPayloadBytes,
                metrics.reuseMetadataBytes,
                metrics.sectionCopyDecodeNanos,
                metrics.sectionCopyDecodeMaxNanos,
                metrics.semanticHashNanos,
                metrics.semanticHashMaxNanos,
                metrics.metadataEncodeNanos,
                metrics.metadataEncodeMaxNanos,
                metrics.replacementNanos,
                metrics.replacementMaxNanos,
                metrics.replacementNanosCurrentTick,
                metrics.replacementNanosMaxTick,
            )
        }
        metrics.resetWindow()
    }
}
