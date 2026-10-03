package calebxzau.rdi.mc.server.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import calebxzau.rdi.mc.chunkcache.ChunkReuseCodec
import calebxzau.rdi.mc.chunkcache.ChunkTerrainCodec
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheCancelPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheContextPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheOfferPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheResultPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheRetirePayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheReusePayload
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.registration.NetworkRegistry
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.UUID

/** Main-thread-owned state and send hook for optional cached-terrain reuse. */
object ChunkCacheServerService {
    private val logger = LoggerFactory.getLogger("rdi")
    private val random = SecureRandom()
    private val sessions = mutableMapOf<UUID, Session>()
    private var budgetTick = Long.MIN_VALUE
    private var budgetUsedNanos = 0L

    private data class Session(
        var epoch: UUID,
        var dimension: ResourceLocation,
        val ledger: ChunkCacheOfferLedger = ChunkCacheOfferLedger(),
        val forceFull: ChunkCacheRepairs = ChunkCacheRepairs(),
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
        ) { x, z ->
            dimensionMatches && session.forceFull.find(x, z) == null &&
                withinPrefetchView(player.chunkTrackingView, x, z)
        }
        if (!admission.accepted) retireRejected(player, session, admission.retireIds)
    }

    private fun withinPrefetchView(view: net.minecraft.server.level.ChunkTrackingView, x: Int, z: Int): Boolean {
        if (view !is net.minecraft.server.level.ChunkTrackingView.Positioned) return false
        val center = view.center()
        return net.minecraft.server.level.ChunkTrackingView.isWithinDistance(
            center.x,
            center.z,
            view.viewDistance() + 2,
            x,
            z,
            true,
        )
    }

    private fun retireRejected(player: ServerPlayer, session: Session, ids: List<Long>) {
        if (ids.isNotEmpty()) PacketDistributor.sendToPlayer(player, ChunkCacheRetirePayload(session.epoch, ids.distinct()))
    }

    @JvmStatic
    fun cancel(player: ServerPlayer, payload: ChunkCacheCancelPayload) {
        val session = activeSession(player, payload.epoch, player.level().dimension().location()) ?: return
        if (payload.ids.size > ChunkCacheLimits.MAX_OFFERS) return
        session.ledger.cancelOfferIds(payload.ids)
        // Retire is an explicit fence: clients keep each pinned snapshot until this acknowledgement.
        val awaitingOutcome = payload.ids.filter { session.forceFull.contains(it) || session.ledger.inFlightToken(it) != null }
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

    private fun repair(player: ServerPlayer, session: Session, candidate: ChunkCacheOfferLedger.Token) {
        val position = ChunkPos(candidate.x, candidate.z)
        val level = player.serverLevel()
        if (session.dimension != level.dimension().location() || !player.chunkTrackingView.contains(position)) {
            abandonRepair(player, session, candidate)
            return
        }
        val chunk = level.chunkSource.chunkMap.getChunkToSend(position.toLong())
        if (chunk == null) {
            abandonRepair(player, session, candidate)
            return
        }
        session.forceFull.add(candidate)
        player.connection.chunkSender.markChunkPendingToSend(chunk)
        session.metrics.recordRepairQueued()
        logger.warn("Chunk cache repair queued for player={} chunk=({}, {})", player.scoreboardName, position.x, position.z)
    }

    private fun abandonRepair(
        player: ServerPlayer,
        session: Session,
        token: ChunkCacheOfferLedger.Token,
    ) {
        session.metrics.recordRepairAbandoned()
        session.forceFull.remove(token.id)
        if (session.dimension == player.level().dimension().location()) {
            player.connection.send(net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket(ChunkPos(token.x, token.z)))
        }
        PacketDistributor.sendToPlayer(player, ChunkCacheRetirePayload(session.epoch, listOf(token.id)))
    }

    /** [chunk] is the live chunk the packet was just built from on this thread, when the caller has it. */
    @JvmStatic
    fun replace(listener: ServerGamePacketListenerImpl, packet: Packet<*>, chunk: LevelChunk?): Packet<*> {
        val player = listener.player
        val session = ensureContext(player) ?: return packet
        val hasChunk = packet is ClientboundLevelChunkWithLightPacket ||
            packet is ClientboundBundlePacket && packet.subPackets().any { it is ClientboundLevelChunkWithLightPacket }
        val startNanos = if (hasChunk) System.nanoTime() else 0L
        return try {
            when (packet) {
                is ClientboundLevelChunkWithLightPacket -> replaceChunk(player, session, packet, chunk)
                is ClientboundBundlePacket -> {
                    val packets = mutableListOf<Packet<in net.minecraft.network.protocol.game.ClientGamePacketListener>>()
                    for (nested in packet.subPackets()) {
                        @Suppress("UNCHECKED_CAST")
                        val replacement: Packet<in net.minecraft.network.protocol.game.ClientGamePacketListener> = if (nested is ClientboundLevelChunkWithLightPacket) {
                            replaceChunk(player, session, nested, chunk) as Packet<in net.minecraft.network.protocol.game.ClientGamePacketListener>
                        } else {
                            nested as Packet<in net.minecraft.network.protocol.game.ClientGamePacketListener>
                        }
                        packets.add(replacement)
                    }
                    ClientboundBundlePacket(packets)
                }
                else -> packet
            }
        } catch (failure: Exception) {
            logger.warn("Chunk cache substitution failed for player={}; sending full chunk packet", player.scoreboardName, failure)
            packet
        } finally {
            if (hasChunk) session.metrics.recordReplacement(player.server.tickCount.toLong(), System.nanoTime() - startNanos)
        }
    }

    private fun replaceChunk(
        player: ServerPlayer,
        session: Session,
        packet: ClientboundLevelChunkWithLightPacket,
        chunk: LevelChunk?,
    ): Packet<*> {
        val pos = ChunkPos(packet.x, packet.z)
        val repairToken = session.forceFull.find(pos.x, pos.z)
        if (repairToken != null) {
            session.metrics.recordForcedRepairFullSend()
            logger.info("Chunk cache full repair sent for player={} chunk=({}, {})", player.scoreboardName, pos.x, pos.z)
            return packet
        }
        session.metrics.recordNormalChunkAttempt()
        val candidate = session.ledger.consume(pos.x, pos.z)
            ?: run {
                session.metrics.recordNoOfferFullSend()
                return packet
            }
        val retire = ChunkCacheRetirePayload(session.epoch, listOf(candidate.id))
        if (!player.chunkTrackingView.contains(pos)) {
            session.metrics.recordCandidateFallbackFullSend()
            PacketDistributor.sendToPlayer(player, retire)
            return packet
        }
        if (!reuseBudgetAvailable(player.server.tickCount.toLong())) {
            session.metrics.recordBudgetFallbackFullSend()
            PacketDistributor.sendToPlayer(player, retire)
            return packet
        }
        val budgetStart = System.nanoTime()
        try {
            val level = player.serverLevel()
            val biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME)
            val sectionBuffer = packet.chunkData.readBuffer
            val sectionPayloadBytes = try {
                sectionBuffer.readableBytes()
            } finally {
                sectionBuffer.release()
            }
            if (sectionPayloadBytes > ChunkCacheLimits.MAX_SECTION_BYTES) {
                session.metrics.recordCandidateFallbackFullSend()
                PacketDistributor.sendToPlayer(player, retire)
                return packet
            }
            session.metrics.recordRawSectionPayloadBytes(sectionPayloadBytes)
            val semanticHashStart = System.nanoTime()
            val hash = try {
                if (chunk != null && chunk.pos == pos && chunk.level === level && chunk.sections.size == level.sectionsCount) {
                    // The packet was serialized from this chunk earlier in the same server-thread call.
                    ServerTerrainHashes.chunkHash(chunk, level.minSection, biomeRegistry, session.metrics)
                } else {
                    decodedPacketHash(session, packet, level.sectionsCount, level.minSection, biomeRegistry)
                }
            } finally {
                session.metrics.recordSemanticHash(System.nanoTime() - semanticHashStart)
            }
            if (!hash.contentEquals(candidate.hash)) {
                session.metrics.recordMismatch()
                PacketDistributor.sendToPlayer(player, retire)
                return packet
            }
            val metadataStart = System.nanoTime()
            val metadata = try {
                ChunkReuseCodec.metadata(packet, level.registryAccess())
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
                PacketDistributor.sendToPlayer(player, retire)
                return packet
            }
            session.metrics.recordReuseSent()
            session.metrics.recordReuseMetadataBytes(metadata.size)
            return ClientboundCustomPayloadPacket(reuse)
        } catch (failure: Exception) {
            session.metrics.recordCandidateFallbackFullSend()
            logger.debug("Chunk cache candidate failed player={} chunk=({}, {}); sending full chunk", player.scoreboardName, pos.x, pos.z, failure)
            PacketDistributor.sendToPlayer(player, retire)
            return packet
        } finally {
            budgetUsedNanos += System.nanoTime() - budgetStart
        }
    }

    /** Fallback when no live chunk is available, such as a chunk packet nested in a foreign bundle. */
    private fun decodedPacketHash(
        session: Session,
        packet: ClientboundLevelChunkWithLightPacket,
        sectionCount: Int,
        minSection: Int,
        biomeRegistry: net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome>,
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

    /** Called only after the ordinary packet has been accepted by the connection. */
    @JvmStatic
    fun afterSend(listener: ServerGamePacketListenerImpl, sent: Packet<*>) {
        val player = listener.player
        val session = sessions[player.uuid] ?: return
        val chunks = when (sent) {
            is ClientboundLevelChunkWithLightPacket -> listOf(sent)
            is ClientboundBundlePacket -> sent.subPackets().filterIsInstance<ClientboundLevelChunkWithLightPacket>()
            else -> emptyList()
        }
        val retired = mutableListOf<Long>()
        for (chunk in chunks) {
            val token = session.forceFull.completeFull(chunk.x, chunk.z) ?: continue
            session.metrics.recordRepairCompleted()
            retired += token.id
        }
        if (retired.isNotEmpty()) PacketDistributor.sendToPlayer(player, ChunkCacheRetirePayload(session.epoch, retired))
    }

    @JvmStatic
    fun tick(player: ServerPlayer) {
        val session = ensureContext(player) ?: return
        session.metrics.advanceReplacementTick(player.server.tickCount.toLong())
        val now = System.currentTimeMillis()
        val expired = session.ledger.expire(now)
        val expiredOffers = expired.offers.map { it.id }
        if (expiredOffers.isNotEmpty()) PacketDistributor.sendToPlayer(player, ChunkCacheRetirePayload(session.epoch, expiredOffers))
        val view = player.chunkTrackingView
        val expiredFlights = expired.inFlight
        for (candidate in expiredFlights) {
            if (candidate.expiresAt <= now) {
                session.metrics.recordReuseTimeout()
                repair(player, session, candidate)
            }
        }
        val droppedFlights = session.ledger.retireInFlightOutside { x, z -> view.contains(x, z) }
        droppedFlights.forEach { abandonRepair(player, session, it) }
        val dropped = session.ledger.retireOffersOutside { x, z -> withinPrefetchView(view, x, z) }
        if (dropped.isNotEmpty()) PacketDistributor.sendToPlayer(player, ChunkCacheRetirePayload(session.epoch, dropped))
        session.forceFull.outside { x, z -> view.contains(x, z) }.forEach { abandonRepair(player, session, it) }
        if (player.server.tickCount.toLong() - session.metricTick >= 1_200L) {
            val metrics = session.metrics
            if (metrics.hasWindowActivity()) {
                logger.info(
                    "Chunk cache minute player={} normalAttempts={} noOfferFullSends={} candidateFallbackFullSends={} forcedRepairFullSends={} mismatches={} reuseSent={} reuseConfirmed={} clientFailure={} reuseTimeout={} repairQueued={} repairCompleted={} repairAbandonedEvents={} budgetFallbackFullSends={} sectionHashHits={} sectionHashMisses={} sectionHashStale={} rawSectionPayloadBytes={} reuseMetadataBytes={} sectionCopyDecodeNanosTotal={} sectionCopyDecodeNanosMax={} semanticHashNanosTotal={} semanticHashNanosMax={} metadataEncodeNanosTotal={} metadataEncodeNanosMax={} replacementNanosTotal={} replacementNanosMax={} replacementNanosCurrentTick={} replacementNanosMaxTick={} replacementTickBoundary=perPlayerSessionServerTick",
                    player.scoreboardName,
                    metrics.normalChunkAttempts,
                    metrics.noOfferFullSends,
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
            session.metricTick = player.server.tickCount.toLong()
            metrics.resetWindow()
        }
    }

    @JvmStatic
    fun reset(player: ServerPlayer) {
        sessions.remove(player.uuid)
    }

    @JvmStatic
    fun initialize(player: ServerPlayer) {
        ensureContext(player)
    }

    @JvmStatic
    fun clear() {
        sessions.clear()
        budgetTick = Long.MIN_VALUE
        budgetUsedNanos = 0
    }

    private fun activeSession(player: ServerPlayer, epoch: UUID, dimension: ResourceLocation): Session? {
        val session = ensureContext(player) ?: return null
        if (session.epoch != epoch || session.dimension != dimension || dimension != player.level().dimension().location()) return null
        return session
    }

    private fun ensureContext(player: ServerPlayer): Session? {
        val listener = player.connection
        if (!supportsProtocol(listener)) return null
        val dimension = player.level().dimension().location()
        val existing = sessions[player.uuid]
        if (existing != null && existing.dimension == dimension) return existing
        val session = Session(newUuidV7(), dimension)
        sessions[player.uuid] = session
        PacketDistributor.sendToPlayer(player, ChunkCacheContextPayload(session.epoch, dimension))
        return session
    }

    private fun supportsProtocol(listener: ServerGamePacketListenerImpl): Boolean =
        NetworkRegistry.hasChannel(listener, ChunkCacheContextPayload.TYPE.id()) &&
            NetworkRegistry.hasChannel(listener, ChunkCacheOfferPayload.TYPE.id()) &&
            NetworkRegistry.hasChannel(listener, ChunkCacheRetirePayload.TYPE.id()) &&
            NetworkRegistry.hasChannel(listener, ChunkCacheCancelPayload.TYPE.id()) &&
            NetworkRegistry.hasChannel(listener, ChunkCacheResultPayload.TYPE.id()) &&
            NetworkRegistry.hasChannel(listener, ChunkCacheReusePayload.TYPE.id())

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
}
