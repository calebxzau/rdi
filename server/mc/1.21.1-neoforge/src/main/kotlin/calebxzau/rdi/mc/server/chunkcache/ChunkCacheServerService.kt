package calebxzau.rdi.mc.server.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import calebxzau.rdi.mc.chunkcache.ChunkReuseCodec
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheAbsentPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheCancelPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheContextPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheOfferPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheResultPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheRetirePayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheReusePayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheWarmupPayload
import calebxzau.rdi.mc.server.mixin.APlayerChunkSender
import it.unimi.dsi.fastutil.longs.LongSet
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ChunkMap
import net.minecraft.server.level.ChunkTrackingView
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.registration.NetworkRegistry
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Main-thread-owned state and send hook for optional cached-terrain reuse on NeoForge 1.21.1.
 *
 * Chunks leave through `PlayerChunkSender` batches; warm-up chooses from its pending set, and a failed reuse is
 * repaired by queueing the chunk there again. The version-independent steps live in [ChunkCacheServerSession]
 * and [ChunkCacheReuseSender].
 */
object ChunkCacheServerService {
    private val logger = LoggerFactory.getLogger("rdi")
    private val sessions = mutableMapOf<UUID, Session>()
    private val sentChunks = ChunkCacheSentChunks()
    private val budget = ChunkCacheReuseBudget()
    private val warmupMillis = ChunkCacheWarmup.configuredMillis(ChunkCacheWarmupPayload.MAX_DURATION_MILLIS.toLong())

    private class Session(
        val playerName: String,
        epoch: UUID,
        dimension: ResourceLocation,
        capacity: Int,
        offersPerTick: Int,
        warmup: ChunkCacheWarmup?,
        metricTick: Long,
    ) : ChunkCacheServerSession(epoch, dimension, capacity, offersPerTick, warmup, metricTick) {
        /** Repairs waiting for their full chunk to leave through the chunk sender. */
        val forceFull = ChunkCacheRepairs()
    }

    @JvmStatic
    fun offer(player: ServerPlayer, payload: ChunkCacheOfferPayload) {
        val session = activeSession(player, payload.epoch, payload.dimension) ?: return
        val dimensionMatches = payload.dimension == player.level().dimension().location()
        val retire = session.admitOffers(payload.entries, player.server.tickCount.toLong(), System.currentTimeMillis()) { x, z ->
            dimensionMatches && session.forceFull.find(x, z) == null && withinPrefetchView(player.chunkTrackingView, x, z)
        }
        sendRetire(player, session, retire)
    }

    private fun withinPrefetchView(view: ChunkTrackingView, x: Int, z: Int): Boolean {
        if (view !is ChunkTrackingView.Positioned) return false
        val center = view.center()
        return ChunkTrackingView.isWithinDistance(center.x, center.z, view.viewDistance() + 2, x, z, true)
    }

    /** Split to the wire limit, which older clients still enforce. */
    private fun sendRetire(player: ServerPlayer, session: Session, ids: List<Long>) {
        ids.chunked(ChunkCacheLimits.MAX_IDS_PER_MESSAGE).forEach { PacketDistributor.sendToPlayer(player, ChunkCacheRetirePayload(session.epoch, it)) }
    }

    /** The client will not offer these chunks; while warming up they may now be sent in full. */
    @JvmStatic
    fun absent(player: ServerPlayer, payload: ChunkCacheAbsentPayload) {
        val session = activeSession(player, payload.epoch, payload.dimension) ?: return
        val warmup = session.warmup ?: return
        val view = player.chunkTrackingView
        warmup.markAbsent(payload.positions.filter { withinPrefetchView(view, ChunkPos.getX(it), ChunkPos.getZ(it)) })
    }

    /**
     * Chooses this tick's chunks from [pending] while the session warms up; null means send them the vanilla way.
     * Called by `PlayerChunkSender.collectChunksToSend` with the same quota and player position.
     */
    @JvmStatic
    fun selectWarmup(player: ServerPlayer, pending: LongSet, quota: Int, center: ChunkPos, chunkMap: ChunkMap): List<LevelChunk>? {
        val session = sessions[player.uuid] ?: return null
        val warmup = session.warmup ?: return null
        if (session.dimension != player.level().dimension().location()) return null
        val now = System.currentTimeMillis()
        if (!warmup.active(now)) {
            session.finishWarmup(logger, player.scoreboardName, pending.size, now, budget.averageReuseNanos)
            return null
        }
        val positions = warmup.select(
            pending, quota, center.x, center.z,
            budget.allowance(player.server.tickCount.toLong(), ChunkCacheLimits.WARMUP_MAX_SERVER_REUSE_NANOS_PER_TICK),
            { session.ledger.hasOffer(ChunkPos.getX(it), ChunkPos.getZ(it)) },
            { chunkMap.getChunkToSend(it) != null },
        )
        return positions.mapNotNull(chunkMap::getChunkToSend)
    }

    @JvmStatic
    fun cancel(player: ServerPlayer, payload: ChunkCacheCancelPayload) {
        val session = activeSession(player, payload.epoch, player.level().dimension().location()) ?: return
        sendRetire(player, session, session.cancelOffers(payload.ids) { session.forceFull.contains(it) })
    }

    @JvmStatic
    fun result(player: ServerPlayer, payload: ChunkCacheResultPayload) {
        val session = activeSession(player, payload.epoch, player.level().dimension().location()) ?: return
        when (val outcome = session.resolveResult(payload.id, payload.x, payload.z, payload.hash, payload.success, System.currentTimeMillis())) {
            is ChunkCacheServerSession.ResultOutcome.Confirmed -> sendRetire(player, session, listOf(outcome.id))
            is ChunkCacheServerSession.ResultOutcome.Repair -> repair(player, session, outcome.token)
            ChunkCacheServerSession.ResultOutcome.Ignored -> Unit
        }
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
        // A repair is a full send the client waits for; warm-up must not hold it back.
        session.warmup?.markAbsent(listOf(position.toLong()))
        player.connection.chunkSender.markChunkPendingToSend(chunk)
        session.metrics.recordRepairQueued()
        logger.warn("Chunk cache repair queued for player={} chunk=({}, {})", player.scoreboardName, position.x, position.z)
    }

    private fun abandonRepair(player: ServerPlayer, session: Session, token: ChunkCacheOfferLedger.Token) {
        session.metrics.recordRepairAbandoned()
        session.forceFull.remove(token.id)
        if (session.dimension == player.level().dimension().location()) {
            player.connection.send(ClientboundForgetLevelChunkPacket(ChunkPos(token.x, token.z)))
        }
        sendRetire(player, session, listOf(token.id))
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
                    val packets = mutableListOf<Packet<in ClientGamePacketListener>>()
                    for (nested in packet.subPackets()) {
                        @Suppress("UNCHECKED_CAST")
                        val replacement: Packet<in ClientGamePacketListener> = if (nested is ClientboundLevelChunkWithLightPacket) {
                            replaceChunk(player, session, nested, chunk) as Packet<in ClientGamePacketListener>
                        } else {
                            nested as Packet<in ClientGamePacketListener>
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
        if (session.forceFull.find(pos.x, pos.z) != null) {
            session.metrics.recordForcedRepairFullSend()
            logger.info("Chunk cache full repair sent for player={} chunk=({}, {})", player.scoreboardName, pos.x, pos.z)
            return packet
        }
        val candidate = session.consumeCandidate(sentChunks, player.uuid, pos.x, pos.z) ?: return packet
        if (!player.chunkTrackingView.contains(pos)) {
            session.metrics.recordCandidateFallbackFullSend()
            sendRetire(player, session, listOf(candidate.id))
            return packet
        }
        val level = player.serverLevel()
        val reuse = ChunkCacheReuseSender.attempt(
            session, budget, level, packet, chunk, candidate, player.server.tickCount.toLong(), player.scoreboardName,
        ) { ChunkReuseCodec.metadata(it, level.registryAccess()) }
        if (reuse == null) {
            sendRetire(player, session, listOf(candidate.id))
            return packet
        }
        return ClientboundCustomPayloadPacket(reuse)
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
        sendRetire(player, session, retired)
    }

    @JvmStatic
    fun tick(player: ServerPlayer) {
        val session = ensureContext(player) ?: return
        val tick = player.server.tickCount.toLong()
        session.metrics.advanceReplacementTick(tick)
        val now = System.currentTimeMillis()
        if (session.warmup?.active(now) == false) {
            val pending = (player.connection.chunkSender as APlayerChunkSender).`rdi$pendingChunks`().size
            session.finishWarmup(logger, player.scoreboardName, pending, now, budget.averageReuseNanos)
        }
        val view = player.chunkTrackingView
        val expiry = session.expire(
            now,
            inView = { x, z -> view.contains(x, z) },
            inPrefetch = { x, z -> withinPrefetchView(view, x, z) },
        )
        sendRetire(player, session, expiry.retire)
        expiry.repair.forEach { repair(player, session, it) }
        expiry.abandon.forEach { abandonRepair(player, session, it) }
        session.forceFull.outside { x, z -> view.contains(x, z) }.forEach { abandonRepair(player, session, it) }
        session.logMinuteIfDue(logger, player.scoreboardName, tick)
    }

    @JvmStatic
    fun reset(player: ServerPlayer) {
        sessions.remove(player.uuid)?.logMetricsWindow(logger, "logout", player.scoreboardName)
    }

    /** Respawn and dimension changes start a new session; the old one's partial window is kept in the log. */
    @JvmStatic
    fun restart(player: ServerPlayer) {
        sessions.remove(player.uuid)?.logMetricsWindow(logger, "session-replaced", player.scoreboardName)
        ensureContext(player)
    }

    @JvmStatic
    fun initialize(player: ServerPlayer) {
        ensureContext(player)
    }

    @JvmStatic
    fun clear() {
        sessions.values.forEach { it.logMetricsWindow(logger, "server-stop", it.playerName) }
        sessions.clear()
        sentChunks.clear()
        budget.clear()
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
        existing?.logMetricsWindow(logger, "session-replaced", player.scoreboardName)
        val warm = supportsWarmup(listener)
        val now = System.currentTimeMillis()
        // Start the window now; with 0 a new session would close its first "minute" on its first tick.
        val session = Session(
            player.scoreboardName,
            ChunkCacheEpochs.newUuidV7(),
            dimension,
            capacity = if (warm) ChunkCacheLimits.MAX_OFFERS else ChunkCacheLimits.LEGACY_MAX_OFFERS,
            offersPerTick = if (warm) ChunkCacheLimits.MAX_OFFERS_PER_TICK else ChunkCacheLimits.LEGACY_MAX_OFFERS_PER_TICK,
            warmup = if (warm && warmupMillis > 0) ChunkCacheWarmup(now, warmupMillis) else null,
            metricTick = player.server.tickCount.toLong(),
        )
        sessions[player.uuid] = session
        PacketDistributor.sendToPlayer(player, ChunkCacheContextPayload(session.epoch, dimension))
        if (warm) {
            PacketDistributor.sendToPlayer(
                player,
                ChunkCacheWarmupPayload(session.epoch, session.capacity, warmupMillis.toInt(), ChunkCacheLimits.WARMUP_IMMEDIATE_RADIUS),
            )
        }
        return session
    }

    private fun supportsProtocol(listener: ServerGamePacketListenerImpl): Boolean =
        NetworkRegistry.hasChannel(listener, ChunkCacheContextPayload.TYPE.id()) &&
            NetworkRegistry.hasChannel(listener, ChunkCacheOfferPayload.TYPE.id()) &&
            NetworkRegistry.hasChannel(listener, ChunkCacheRetirePayload.TYPE.id()) &&
            NetworkRegistry.hasChannel(listener, ChunkCacheCancelPayload.TYPE.id()) &&
            NetworkRegistry.hasChannel(listener, ChunkCacheResultPayload.TYPE.id()) &&
            NetworkRegistry.hasChannel(listener, ChunkCacheReusePayload.TYPE.id())

    /** Warm-up and the larger capacity need both payloads; older clients keep the original protocol. */
    private fun supportsWarmup(listener: ServerGamePacketListenerImpl): Boolean =
        NetworkRegistry.hasChannel(listener, ChunkCacheWarmupPayload.TYPE.id()) &&
            NetworkRegistry.hasChannel(listener, ChunkCacheAbsentPayload.TYPE.id())
}
