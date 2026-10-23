package calebxzau.rdi.mc.server.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import calebxzau.rdi.mc.chunkcache.ChunkReuseCodec
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheAbsentPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheCancelPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheChannel
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheContextPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheOfferPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCachePausedPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheResultPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheRetirePayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheWarmupPayload
import calebxzau.rdi.mc.server.chunkcache.mixin.AChunkMap
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ChunkMap
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk
import org.apache.commons.lang3.mutable.MutableObject
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Main-thread-owned state and send hook for optional cached-terrain reuse on Forge 1.20.1.
 *
 * Unlike 1.21, 1.20.1 sends each chunk synchronously from `ChunkMap.playerLoadedChunk` with no
 * batch acknowledgement, so a failed reuse is repaired by sending the current full chunk at once.
 * Without a pending-send queue, warm-up holds that whole call back and makes it again on release.
 * The version-independent steps live in [ChunkCacheServerSession] and [ChunkCacheReuseSender].
 */
object ChunkCacheServerService {
    private val logger = LoggerFactory.getLogger("rdi")
    private val sessions = mutableMapOf<UUID, Session>()
    private val sentChunks = ChunkCacheSentChunks()
    private val budget = ChunkCacheReuseBudget()
    private val warmupMillis = ChunkCacheWarmup.configuredMillis(ChunkCacheWarmupPayload.MAX_DURATION_MILLIS.toLong())
    /** Set while [releaseHeld] makes a held `playerLoadedChunk` call, so [hold] lets it through. */
    private var releasing = false

    private class Session(
        /** `PlayerList.respawn` sends the new player's chunks before any event, so identity marks a respawn. */
        val player: ServerPlayer,
        epoch: UUID,
        dimension: ResourceLocation,
        capacity: Int,
        offersPerTick: Int,
        warmup: ChunkCacheWarmup?,
        metricTick: Long,
    ) : ChunkCacheServerSession(epoch, dimension, capacity, offersPerTick, warmup, metricTick) {
        val held = ChunkCacheHeldChunks()
    }

    @JvmStatic
    fun offer(player: ServerPlayer, payload: ChunkCacheOfferPayload) {
        val session = activeSession(player, payload.epoch, payload.dimension) ?: return
        val dimensionMatches = payload.dimension == player.level().dimension().location()
        val retire = session.admitOffers(payload.entries, player.server.tickCount.toLong(), System.currentTimeMillis()) { x, z ->
            dimensionMatches && withinView(player, x, z, PREFETCH_MARGIN)
        }
        sendRetire(player, session, retire)
    }

    /** Mirrors 1.20.1 `ChunkMap` tracking: the server view distance around the player's last section. */
    private fun withinView(player: ServerPlayer, x: Int, z: Int, margin: Int): Boolean {
        val center = player.lastSectionPos
        val viewDistance = player.server.playerList.viewDistance.coerceIn(2, 32)
        return ChunkMap.isChunkInRange(x, z, center.x(), center.z(), viewDistance + margin)
    }

    /** Split to the wire limit, which older clients still enforce. */
    private fun sendRetire(player: ServerPlayer, session: Session, ids: List<Long>) {
        ids.chunked(ChunkCacheLimits.MAX_IDS_PER_MESSAGE).forEach { ChunkCacheChannel.sendToPlayer(player, ChunkCacheRetirePayload(session.epoch, it)) }
    }

    /** The client will not offer these chunks; while warming up they may now be sent in full. */
    @JvmStatic
    fun absent(player: ServerPlayer, payload: ChunkCacheAbsentPayload) {
        val session = activeSession(player, payload.epoch, payload.dimension) ?: return
        val warmup = session.warmup ?: return
        warmup.markAbsent(payload.positions.filter { withinView(player, ChunkPos.getX(it), ChunkPos.getZ(it), PREFETCH_MARGIN) })
    }

    /** The client keeps receiving full chunks until it reconnects; the reason is only logged. */
    @JvmStatic
    fun paused(player: ServerPlayer, payload: ChunkCachePausedPayload) {
        val session = activeSession(player, payload.epoch, player.level().dimension().location()) ?: return
        if (session.clientPaused) return
        session.clientPaused = true
        logger.warn("Chunk cache paused on the client player={} reason={}", player.scoreboardName, payload.reason)
    }

    /**
     * Called at the start of `ChunkMap.playerLoadedChunk`; true holds the chunk back while the session warms up.
     * [releaseHeld] later makes the same call for it.
     */
    @JvmStatic
    fun hold(player: ServerPlayer, chunk: LevelChunk): Boolean {
        if (releasing) return false
        // This runs inside chunk tracking; no failure may escape into it.
        return try {
            val session = ensureContext(player) ?: return false
            val warmup = session.warmup ?: return false
            if (session.dimension != player.level().dimension().location() || !warmup.active(System.currentTimeMillis())) return false
            val position = chunk.pos.toLong()
            val center = player.lastSectionPos
            val allowance = budget.allowance(player.server.tickCount.toLong(), ChunkCacheLimits.WARMUP_MAX_SERVER_REUSE_NANOS_PER_TICK)
            val send = warmup.select(
                listOf(position), 1, center.x(), center.z(), allowance,
                { session.ledger.hasOffer(ChunkPos.getX(it), ChunkPos.getZ(it)) },
                { true },
            )
            if (send.isNotEmpty()) return false
            session.held.hold(position)
            true
        } catch (failure: Exception) {
            logger.warn("Chunk cache warm-up hold failed for player={}; sending the chunk now", player.scoreboardName, failure)
            false
        }
    }

    /** Called when [player] stops tracking [pos] in [level]; true when it was held, so it was never sent. */
    @JvmStatic
    fun forgetHeld(player: ServerPlayer, level: ServerLevel, pos: ChunkPos): Boolean {
        val session = sessions[player.uuid] ?: return false
        if (session.player !== player || session.dimension != level.dimension().location()) return false
        return session.held.forget(pos.toLong())
    }

    /**
     * Sends held chunks the client has offered or declined, nearest first, or all of them once warm-up ended.
     * Offered chunks beyond this tick's reuse budget wait for a later tick, as on 1.21.
     */
    private fun releaseHeld(player: ServerPlayer, session: Session) {
        val now = System.currentTimeMillis()
        if (session.warmup?.active(now) == false) {
            session.finishWarmup(logger, player.scoreboardName, session.held.size, now, budget.averageReuseNanos)
        }
        if (session.held.isEmpty()) return
        val level = player.serverLevel()
        if (session.dimension != level.dimension().location()) {
            session.held.takeAll()
            return
        }
        val chunkMap = level.chunkSource.chunkMap as AChunkMap
        val center = player.lastSectionPos
        val viewDistance = chunkMap.`rdi$viewDistance`()
        fun liveChunk(position: Long): LevelChunk? = chunkMap.`rdi$getVisibleChunkIfPresent`(position)?.tickingChunk
        val pending = session.held.prune { position ->
            ChunkMap.isChunkInRange(ChunkPos.getX(position), ChunkPos.getZ(position), center.x(), center.z(), viewDistance) &&
                liveChunk(position) != null
        }
        val positions = session.warmup?.select(
            pending, Int.MAX_VALUE, center.x(), center.z(),
            budget.allowance(player.server.tickCount.toLong(), ChunkCacheLimits.WARMUP_MAX_SERVER_REUSE_NANOS_PER_TICK),
            { session.ledger.hasOffer(ChunkPos.getX(it), ChunkPos.getZ(it)) },
            { true },
        ) ?: session.held.takeAll().asList()
        for (position in positions) {
            session.held.release(position)
            val chunk = liveChunk(position) ?: continue
            releasing = true
            try {
                chunkMap.`rdi$playerLoadedChunk`(player, MutableObject(), chunk)
            } catch (failure: Exception) {
                logger.error("Chunk cache could not send held chunk player={} chunk=({}, {})", player.scoreboardName,
                    ChunkPos.getX(position), ChunkPos.getZ(position), failure)
            } finally {
                releasing = false
            }
        }
    }

    @JvmStatic
    fun cancel(player: ServerPlayer, payload: ChunkCacheCancelPayload) {
        val session = activeSession(player, payload.epoch, player.level().dimension().location()) ?: return
        sendRetire(player, session, session.cancelOffers(payload.ids))
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
        sendRetire(player, session, listOf(token.id))
        logger.warn("Chunk cache repair sent for player={} chunk=({}, {})", player.scoreboardName, token.x, token.z)
    }

    private fun abandonRepair(player: ServerPlayer, session: Session, token: ChunkCacheOfferLedger.Token) {
        session.metrics.recordRepairAbandoned()
        if (session.dimension == player.level().dimension().location()) {
            player.connection.send(ClientboundForgetLevelChunkPacket(token.x, token.z))
        }
        sendRetire(player, session, listOf(token.id))
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
        val candidate = session.consumeCandidate(sentChunks, player.uuid, packet.x, packet.z) ?: return packet
        val reuse = ChunkCacheReuseSender.attempt(
            session, budget, player.serverLevel(), packet, chunk, candidate, player.server.tickCount.toLong(),
            player.scoreboardName, ChunkReuseCodec::metadata,
        )
        if (reuse == null) {
            sendRetire(player, session, listOf(candidate.id))
            return packet
        }
        return ChunkCacheChannel.toClientPacket(reuse)
    }

    @JvmStatic
    fun tick(player: ServerPlayer) {
        try {
            tickSession(player)
        } catch (failure: Exception) {
            logger.error("Chunk cache maintenance failed for player={}; resetting its session", player.scoreboardName, failure)
            val session = sessions.remove(player.uuid) ?: return
            // Held chunks were never sent; without the session nothing else would send them.
            session.warmup = null
            try {
                releaseHeld(player, session)
            } catch (releaseFailure: Exception) {
                logger.error("Chunk cache could not send held chunks for player={}", player.scoreboardName, releaseFailure)
            }
        }
    }

    private fun tickSession(player: ServerPlayer) {
        val session = ensureContext(player) ?: return
        val tick = player.server.tickCount.toLong()
        session.metrics.advanceReplacementTick(tick)
        val now = System.currentTimeMillis()
        val expiry = session.expire(
            now,
            inView = { x, z -> withinView(player, x, z, 0) },
            inPrefetch = { x, z -> withinView(player, x, z, PREFETCH_MARGIN) },
        )
        sendRetire(player, session, expiry.retire)
        expiry.repair.forEach { repair(player, session, it) }
        expiry.abandon.forEach { abandonRepair(player, session, it) }
        releaseHeld(player, session)
        session.logMinuteIfDue(logger, player.scoreboardName, tick)
    }

    @JvmStatic
    fun reset(player: ServerPlayer) {
        sessions.remove(player.uuid)?.logMetricsWindow(logger, "logout", player.scoreboardName)
    }

    @JvmStatic
    fun initialize(player: ServerPlayer) {
        ensureContext(player)
    }

    @JvmStatic
    fun clear() {
        sessions.values.forEach { it.logMetricsWindow(logger, "server-stop", it.player.scoreboardName) }
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
        // Without the send hooks no session could ever reuse terrain; the client then never starts one.
        if (!ChunkCacheCompat.isAvailable()) return null
        val connection = player.connection.connection
        if (!ChunkCacheChannel.isRemotePresent(connection)) return null
        val dimension = player.level().dimension().location()
        val existing = sessions[player.uuid]
        if (existing != null && existing.player === player && existing.dimension == dimension) return existing
        // Respawn and dimension changes replace the session; keep its partial window.
        existing?.logMetricsWindow(logger, "session-replaced", existing.player.scoreboardName)
        // Warm-up and the larger capacity need both channels; a local client never prepares candidates.
        val warm = ChunkCacheChannel.isWarmupRemotePresent(connection) && !connection.isMemoryConnection
        // Start the window now; with 0 a new session would close its first "minute" on its first tick.
        val session = Session(
            player,
            ChunkCacheEpochs.newUuidV7(),
            dimension,
            capacity = if (warm) ChunkCacheLimits.MAX_OFFERS else ChunkCacheLimits.LEGACY_MAX_OFFERS,
            offersPerTick = if (warm) ChunkCacheLimits.MAX_OFFERS_PER_TICK else ChunkCacheLimits.LEGACY_MAX_OFFERS_PER_TICK,
            warmup = if (warm && warmupMillis > 0) ChunkCacheWarmup(System.currentTimeMillis(), warmupMillis) else null,
            metricTick = player.server.tickCount.toLong(),
        )
        sessions[player.uuid] = session
        ChunkCacheChannel.sendToPlayer(player, ChunkCacheContextPayload(session.epoch, dimension))
        if (warm) {
            ChunkCacheChannel.sendToPlayer(
                player,
                ChunkCacheWarmupPayload(session.epoch, session.capacity, warmupMillis.toInt(), ChunkCacheLimits.WARMUP_IMMEDIATE_RADIUS),
            )
        }
        return session
    }

    private const val PREFETCH_MARGIN = 2
}
