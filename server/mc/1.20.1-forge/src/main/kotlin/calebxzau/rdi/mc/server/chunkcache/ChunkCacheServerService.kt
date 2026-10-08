package calebxzau.rdi.mc.server.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import calebxzau.rdi.mc.chunkcache.ChunkReuseCodec
import calebxzau.rdi.mc.chunkcache.ChunkTerrainCodec
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheCancelPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheChannel
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheContextPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheOfferPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheResultPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheRetirePayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheReusePayload
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ChunkMap
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.LevelChunk
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.UUID

/**
 * Main-thread-owned state and send hook for optional cached-terrain reuse on Forge 1.20.1.
 *
 * Unlike 1.21, 1.20.1 sends each chunk synchronously from `ChunkMap.playerLoadedChunk` with no
 * batch acknowledgement, so a failed reuse is repaired by sending the current full chunk at once.
 */
object ChunkCacheServerService {
    private val logger = LoggerFactory.getLogger("rdi")
    private val random = SecureRandom()
    private val sessions = mutableMapOf<UUID, Session>()
    private val sentChunks = ChunkCacheSentChunks()
    private var budgetTick = Long.MIN_VALUE
    private var budgetUsedNanos = 0L

    private data class Session(
        /** `PlayerList.respawn` sends the new player's chunks before any event, so identity marks a respawn. */
        val player: ServerPlayer,
        var epoch: UUID,
        var dimension: ResourceLocation,
        val ledger: ChunkCacheOfferLedger = ChunkCacheOfferLedger(),
        val metrics: ChunkCacheServerMetrics = ChunkCacheServerMetrics(),
        var offersThisTick: Int = 0,
        var offerTick: Long = Long.MIN_VALUE,
        var metricTick: Long = 0,
    )

    @JvmStatic
    fun offer(player: ServerPlayer, payload: ChunkCacheOfferPayload) {
        val session = activeSession(player, payload.epoch, payload.dimension) ?: return
        if (payload.entries.isEmpty()) return
        val tick = player.server.tickCount.toLong()
        if (session.offerTick != tick) {
            session.offerTick = tick
            session.offersThisTick = 0
        }
        if (session.offersThisTick + payload.entries.size > ChunkCacheLimits.MAX_OFFER_BATCH) {
            session.ledger.rejectIds(payload.entries.map { it.id })
            retireRejected(player, session, payload.entries.map { it.id })
            return
        }
        session.offersThisTick += payload.entries.size
        val dimensionMatches = payload.dimension == player.level().dimension().location()
        val admission = session.ledger.admit(
            payload.entries,
            System.currentTimeMillis() + ChunkCacheLimits.OFFER_TTL_MILLIS,
        ) { x, z -> dimensionMatches && withinView(player, x, z, PREFETCH_MARGIN) }
        if (!admission.accepted) retireRejected(player, session, admission.retireIds)
    }

    /** Mirrors 1.20.1 `ChunkMap` tracking: the server view distance around the player's last section. */
    private fun withinView(player: ServerPlayer, x: Int, z: Int, margin: Int): Boolean {
        val center = player.lastSectionPos
        val viewDistance = player.server.playerList.viewDistance.coerceIn(2, 32)
        return ChunkMap.isChunkInRange(x, z, center.x(), center.z(), viewDistance + margin)
    }

    private fun retireRejected(player: ServerPlayer, session: Session, ids: List<Long>) {
        if (ids.isNotEmpty()) ChunkCacheChannel.sendToPlayer(player, ChunkCacheRetirePayload(session.epoch, ids.distinct()))
    }

    @JvmStatic
    fun cancel(player: ServerPlayer, payload: ChunkCacheCancelPayload) {
        val session = activeSession(player, payload.epoch, player.level().dimension().location()) ?: return
        if (payload.ids.size > ChunkCacheLimits.MAX_OFFERS) return
        session.ledger.cancelOfferIds(payload.ids)
        // Retire is an explicit fence: clients keep each pinned snapshot until this acknowledgement.
        val awaitingOutcome = payload.ids.filter { session.ledger.inFlightToken(it) != null }
        retireRejected(player, session, payload.ids - awaitingOutcome.toSet())
    }

    @JvmStatic
    fun result(player: ServerPlayer, payload: ChunkCacheResultPayload) {
        val session = activeSession(player, payload.epoch, player.level().dimension().location()) ?: return
        val issued = session.ledger.resolve(payload.id, payload.x, payload.z, payload.hash, payload.success) ?: return
        if (issued.token.expiresAt <= System.currentTimeMillis()) {
            session.metrics.recordReuseTimeout()
            if (!payload.success) session.metrics.recordClientFailure()
            repair(player, session, issued.token)
            return
        }
        if (payload.success) {
            session.metrics.recordReuseConfirmed()
            retireRejected(player, session, listOf(payload.id))
            return
        }
        session.metrics.recordClientFailure()
        repair(player, session, issued.token)
    }

    /** Sends the current full chunk now; the retire that follows ends the client's repair fence. */
    private fun repair(player: ServerPlayer, session: Session, token: ChunkCacheOfferLedger.Token) {
        val level = player.serverLevel()
        val chunk = if (session.dimension == level.dimension().location() && withinView(player, token.x, token.z, 0)) {
            level.chunkSource.getChunkNow(token.x, token.z)
        } else {
            null
        }
        if (chunk == null) {
            abandonRepair(player, session, token)
            return
        }
        session.metrics.recordRepairQueued()
        player.connection.send(ClientboundLevelChunkWithLightPacket(chunk, level.lightEngine, null, null))
        session.metrics.recordForcedRepairFullSend()
        session.metrics.recordRepairCompleted()
        ChunkCacheChannel.sendToPlayer(player, ChunkCacheRetirePayload(session.epoch, listOf(token.id)))
        logger.warn("Chunk cache repair sent for player={} chunk=({}, {})", player.scoreboardName, token.x, token.z)
    }

    private fun abandonRepair(player: ServerPlayer, session: Session, token: ChunkCacheOfferLedger.Token) {
        session.metrics.recordRepairAbandoned()
        if (session.dimension == player.level().dimension().location()) {
            player.connection.send(ClientboundForgetLevelChunkPacket(token.x, token.z))
        }
        ChunkCacheChannel.sendToPlayer(player, ChunkCacheRetirePayload(session.epoch, listOf(token.id)))
    }

    /** Called from `ChunkMap.playerLoadedChunk` with the live [chunk] the shared [packet] was built from. */
    @JvmStatic
    fun replace(player: ServerPlayer, packet: Packet<*>, chunk: LevelChunk?): Packet<*> {
        if (packet !is ClientboundLevelChunkWithLightPacket) return packet
        // This runs inside ChunkMap.playerLoadedChunk; no failure may escape into chunk tracking.
        var session: Session? = null
        val startNanos = System.nanoTime()
        return try {
            session = ensureContext(player) ?: return packet
            replaceChunk(player, session, packet, chunk)
        } catch (failure: Exception) {
            logger.warn("Chunk cache substitution failed for player={}; sending full chunk packet", player.scoreboardName, failure)
            packet
        } finally {
            session?.metrics?.recordReplacement(player.server.tickCount.toLong(), System.nanoTime() - startNanos)
        }
    }

    private fun replaceChunk(
        player: ServerPlayer,
        session: Session,
        packet: ClientboundLevelChunkWithLightPacket,
        chunk: LevelChunk?,
    ): Packet<*> {
        val pos = ChunkPos(packet.x, packet.z)
        session.metrics.recordNormalChunkAttempt()
        val revisit = sentChunks.markSent(player.uuid, session.dimension, pos.toLong())
        val candidate = session.ledger.consume(pos.x, pos.z)
            ?: run {
                session.metrics.recordNoOfferFullSend()
                if (revisit) session.metrics.recordNoOfferRevisitFullSend()
                return packet
            }
        val retire = ChunkCacheRetirePayload(session.epoch, listOf(candidate.id))
        if (!reuseBudgetAvailable(player.server.tickCount.toLong())) {
            session.metrics.recordBudgetFallbackFullSend()
            ChunkCacheChannel.sendToPlayer(player, retire)
            return packet
        }
        val budgetStart = System.nanoTime()
        try {
            val level = player.serverLevel()
            val biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME)
            // The packet is shared by every player loading this chunk; its read buffer is a fresh view.
            val sectionBuffer = packet.chunkData.readBuffer
            val sectionPayloadBytes = try {
                sectionBuffer.readableBytes()
            } finally {
                sectionBuffer.release()
            }
            if (sectionPayloadBytes > ChunkCacheLimits.MAX_SECTION_BYTES) {
                session.metrics.recordCandidateFallbackFullSend()
                ChunkCacheChannel.sendToPlayer(player, retire)
                return packet
            }
            session.metrics.recordRawSectionPayloadBytes(sectionPayloadBytes)
            val semanticHashStart = System.nanoTime()
            val hash = try {
                if (chunk != null && chunk.pos == pos && chunk.level === level && chunk.sections.size == level.sectionsCount) {
                    // The packet was built from this chunk earlier in this tracking pass; ChunkWatchEvent handlers for
                    // earlier players may have changed it since, which later block updates correct.
                    ServerTerrainHashes.chunkHash(chunk, level.minSection, biomeRegistry, session.metrics)
                } else {
                    decodedPacketHash(session, packet, level.sectionsCount, level.minSection, biomeRegistry)
                }
            } finally {
                session.metrics.recordSemanticHash(System.nanoTime() - semanticHashStart)
            }
            if (!hash.contentEquals(candidate.hash)) {
                session.metrics.recordMismatch()
                ChunkCacheChannel.sendToPlayer(player, retire)
                return packet
            }
            val metadataStart = System.nanoTime()
            val metadata = try {
                ChunkReuseCodec.metadata(packet)
            } finally {
                session.metrics.recordMetadataEncode(System.nanoTime() - metadataStart)
            }
            val reuse = ChunkCacheReusePayload(
                session.epoch,
                candidate.id,
                session.dimension,
                pos.x,
                pos.z,
                candidate.hash.copyOf(),
                metadata,
            )
            if (!session.ledger.trackReuse(candidate, System.currentTimeMillis() + ChunkCacheLimits.OFFER_TTL_MILLIS)) {
                session.metrics.recordCandidateFallbackFullSend()
                ChunkCacheChannel.sendToPlayer(player, retire)
                return packet
            }
            session.metrics.recordReuseSent()
            session.metrics.recordReuseMetadataBytes(metadata.size)
            return ChunkCacheChannel.toClientPacket(reuse)
        } catch (failure: Exception) {
            session.metrics.recordCandidateFallbackFullSend()
            logger.debug("Chunk cache candidate failed player={} chunk=({}, {}); sending full chunk", player.scoreboardName, pos.x, pos.z, failure)
            ChunkCacheChannel.sendToPlayer(player, retire)
            return packet
        } finally {
            budgetUsedNanos += System.nanoTime() - budgetStart
        }
    }

    /** Fallback when no matching live chunk is available. */
    private fun decodedPacketHash(
        session: Session,
        packet: ClientboundLevelChunkWithLightPacket,
        sectionCount: Int,
        minSection: Int,
        biomeRegistry: Registry<Biome>,
    ): ByteArray {
        val sectionCopyDecodeStart = System.nanoTime()
        val sections = try {
            val sectionBuffer = packet.chunkData.readBuffer
            val sectionBytes = try {
                ByteArray(sectionBuffer.readableBytes()).also { sectionBuffer.readBytes(it) }
            } finally {
                sectionBuffer.release()
            }
            ChunkTerrainCodec.decode(sectionBytes, sectionCount, biomeRegistry)
        } finally {
            session.metrics.recordSectionCopyDecode(System.nanoTime() - sectionCopyDecodeStart)
        }
        return ChunkTerrainCodec.hash(minSection, sections, biomeRegistry)
    }

    private fun reuseBudgetAvailable(tick: Long): Boolean {
        if (budgetTick != tick) {
            budgetTick = tick
            budgetUsedNanos = 0
        }
        return budgetUsedNanos < ChunkCacheLimits.MAX_SERVER_REUSE_NANOS_PER_TICK
    }

    @JvmStatic
    fun tick(player: ServerPlayer) {
        try {
            tickSession(player)
        } catch (failure: Exception) {
            logger.error("Chunk cache maintenance failed for player={}; resetting its session", player.scoreboardName, failure)
            sessions.remove(player.uuid)
        }
    }

    private fun tickSession(player: ServerPlayer) {
        val session = ensureContext(player) ?: return
        session.metrics.advanceReplacementTick(player.server.tickCount.toLong())
        val now = System.currentTimeMillis()
        val expired = session.ledger.expire(now)
        val expiredOffers = expired.offers.map { it.id }
        if (expiredOffers.isNotEmpty()) ChunkCacheChannel.sendToPlayer(player, ChunkCacheRetirePayload(session.epoch, expiredOffers))
        for (candidate in expired.inFlight) {
            if (candidate.expiresAt <= now) {
                session.metrics.recordReuseTimeout()
                repair(player, session, candidate)
            }
        }
        val droppedFlights = session.ledger.retireInFlightOutside { x, z -> withinView(player, x, z, 0) }
        droppedFlights.forEach { abandonRepair(player, session, it) }
        val dropped = session.ledger.retireOffersOutside { x, z -> withinView(player, x, z, PREFETCH_MARGIN) }
        if (dropped.isNotEmpty()) ChunkCacheChannel.sendToPlayer(player, ChunkCacheRetirePayload(session.epoch, dropped))
        if (player.server.tickCount.toLong() - session.metricTick >= 1_200L) {
            logMetricsWindow(session, "minute")
            session.metricTick = player.server.tickCount.toLong()
        }
    }

    /** Logs and clears the current window; [window] is `minute` or why the session ended before its minute closed. */
    private fun logMetricsWindow(session: Session, window: String) {
        val metrics = session.metrics
        if (metrics.hasWindowActivity()) {
            logger.info(
                "Chunk cache {} player={} normalAttempts={} noOfferFullSends={} noOfferRevisitFullSends={} candidateFallbackFullSends={} forcedRepairFullSends={} mismatches={} reuseSent={} reuseConfirmed={} clientFailure={} reuseTimeout={} repairQueued={} repairCompleted={} repairAbandonedEvents={} budgetFallbackFullSends={} sectionHashHits={} sectionHashMisses={} sectionHashStale={} rawSectionPayloadBytes={} reuseMetadataBytes={} sectionCopyDecodeNanosTotal={} sectionCopyDecodeNanosMax={} semanticHashNanosTotal={} semanticHashNanosMax={} metadataEncodeNanosTotal={} metadataEncodeNanosMax={} replacementNanosTotal={} replacementNanosMax={} replacementNanosCurrentTick={} replacementNanosMaxTick={} replacementTickBoundary=perPlayerSessionServerTick",
                window,
                session.player.scoreboardName,
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

    @JvmStatic
    fun reset(player: ServerPlayer) {
        sessions.remove(player.uuid)?.let { logMetricsWindow(it, "logout") }
    }

    @JvmStatic
    fun initialize(player: ServerPlayer) {
        ensureContext(player)
    }

    @JvmStatic
    fun clear() {
        sessions.values.forEach { logMetricsWindow(it, "server-stop") }
        sessions.clear()
        sentChunks.clear()
        budgetTick = Long.MIN_VALUE
        budgetUsedNanos = 0
    }

    private fun activeSession(player: ServerPlayer, epoch: UUID, dimension: ResourceLocation): Session? {
        val session = ensureContext(player) ?: return null
        if (session.epoch != epoch || session.dimension != dimension || dimension != player.level().dimension().location()) return null
        return session
    }

    private fun ensureContext(player: ServerPlayer): Session? {
        if (!ChunkCacheChannel.isRemotePresent(player.connection.connection)) return null
        val dimension = player.level().dimension().location()
        val existing = sessions[player.uuid]
        if (existing != null && existing.player === player && existing.dimension == dimension) return existing
        // Respawn and dimension changes replace the session; keep its partial window.
        existing?.let { logMetricsWindow(it, "session-replaced") }
        // Start the window now; with 0 a new session would close its first "minute" on its first tick.
        val session = Session(player, newUuidV7(), dimension, metricTick = player.server.tickCount.toLong())
        sessions[player.uuid] = session
        ChunkCacheChannel.sendToPlayer(player, ChunkCacheContextPayload(session.epoch, dimension))
        return session
    }

    private fun newUuidV7(): UUID {
        val bytes = ByteArray(16).also(random::nextBytes)
        val millis = System.currentTimeMillis()
        for (index in 0..5) bytes[index] = (millis ushr (40 - index * 8)).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        var most = 0L
        var least = 0L
        for (index in 0..7) most = (most shl 8) or (bytes[index].toLong() and 0xff)
        for (index in 8..15) least = (least shl 8) or (bytes[index].toLong() and 0xff)
        return UUID(most, least)
    }

    private const val PREFETCH_MARGIN = 2
}
