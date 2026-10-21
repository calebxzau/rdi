package calebxzau.rdi.mc.client.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import calebxzau.rdi.mc.chunkcache.ChunkTerrainCodec
import calebxzau.rdi.mc.chunkcache.ChunkTerrainCodec.PreparedTerrain
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheAbsentPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheCancelPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheContextPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheOffer
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheOfferPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCachePausedPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheResultPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheRetirePayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheReusePayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheWarmupPayload
import com.mojang.logging.LogUtils
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.world.level.ChunkPos
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cache offers are published only after detached terrain is decoded, hashed and pinned in RAM.
 *
 * Shared by every Minecraft version; [ChunkCacheCompat] and [ChunkCacheViewRange] hold the version's APIs. Messages
 * arrive on the game thread from the version's network glue, which resolves the listener they belong to.
 */
object ChunkCacheClient {
    private val logger = LogUtils.getLogger()
    private val preparation = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "rdi-chunk-cache-prepare").apply { isDaemon = true }
    }
    /**
     * Preparations in flight after warm-up. Each one waits about 30-40ms on the cache read and the main-thread hop,
     * so 2 kept up with only about 50 chunks a second and a moving player received many cached chunks in full.
     */
    private const val MAX_PREPARING = 8
    /**
     * While the server holds chunks back, preparation decides how soon they arrive. Each preparation waits on the
     * cache read queue and a main-thread hop, so throughput is bound by how many are in flight, not by CPU.
     * Stays below the writer's read queue limit so warm-up reads are not refused.
     */
    private const val WARMUP_MAX_PREPARING = 32
    private const val RETRY_MILLIS = 10_000L
    private const val REPAIR_TIMEOUT_MILLIS = 15_000L
    private var current: State? = null
    private val outstandingPreparations = AtomicInteger()

    private class State(
        val listener: ClientPacketListener,
        val level: ClientLevel,
        val capture: RdiChunkCache.PacketCaptureContext,
        val epoch: UUID,
    ) {
        val pins = ChunkCacheOfferPins<PreparedTerrain>(ChunkCacheLimits.MAX_OFFERS, preparedBytesLimit())
        /** Raised by the server's warm-up announcement; older servers accept only the legacy amounts. */
        var capacity = ChunkCacheLimits.LEGACY_MAX_OFFERS
        var offersPerTick = ChunkCacheLimits.LEGACY_MAX_OFFERS_PER_TICK
        /** [nowMillis] until which the server waits for offers or absent reports; 0 without warm-up. */
        var warmupEndsAt = 0L
        /** Chunks a full or reused packet delivered in this context; loaded chunks from before it may be offered. */
        val received = LongOpenHashSet()
        val absent = LongArrayList()
        var absentReported = 0L
        /** Counters when warm-up began, so its summary shows only what happened during it. */
        var warmupBaseline: LongArray? = null
        var warmupStartedNanos = 0L
        var firstOfferNanos = 0L
        var warmupLatencyCount = 0L
        var warmupPrepareLatencyNanos = 0L
        var warmupMaxPrepareLatencyNanos = 0L
        var warmupMainHopNanos = 0L
        var warmupMaxMainHopNanos = 0L
        val preparing = LongOpenHashSet()
        val retryAfter = Long2LongOpenHashMap()
        val outbox = ChunkCacheOutbox<ChunkCacheOffer> { it.id }
        val repairs = Long2ObjectOpenHashMap<Repair>()
        val candidates = ChunkCacheCandidateScan()
        var centerX = Int.MIN_VALUE
        var centerZ = Int.MIN_VALUE
        var viewDistance = -1
        var applyingReuse = false
        @Volatile var closed = false
        var hits = 0L
        var reuseFailures = 0L
        var repairsCompleted = 0L
        var preparationFailures = 0L
        var preparationAttempts = 0L
        var cacheAbsent = 0L
        var incompatible = 0L
        var preparedTooLate = 0L
        var preparedOutsideView = 0L
        var pinRejected = 0L
        var offersSent = 0L
        var offersExpired = 0L
        /** Offers the server retired without a reuse or a cancel: refused, dropped from its range, or sent in full. */
        var offersRetired = 0L
        var prepareNanos = 0L
        var maxPrepareNanos = 0L
        var restoreNanos = 0L
        var captureNanos = 0L
        var applyNanos = 0L
        var reuseNanos = 0L
        var maxReuseNanos = 0L
        var prepared = 0L
        var lastLog = System.nanoTime()
    }

    private data class Repair(val id: Long, val startedAt: Long)

    fun context(payload: ChunkCacheContextPayload, listener: ClientPacketListener) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        if (minecraft.connection !== listener || level.dimension().location() != payload.dimension) return
        close()
        val capture = (listener as? ChunkCachePacketAccess)?.`rdi$cacheContext`() ?: return
        if (minecraft.hasSingleplayerServer()) return
        if (!RdiChunkCache.isCacheActive(capture)) {
            reportPause(listener, payload.epoch, capture)
            return
        }
        current = State(listener, level, capture, payload.epoch)
    }

    fun warmup(payload: ChunkCacheWarmupPayload, listener: ClientPacketListener) {
        val state = state(listener) ?: return
        if (payload.epoch != state.epoch) return
        state.capacity = payload.capacity.coerceAtMost(ChunkCacheLimits.MAX_OFFERS)
        state.offersPerTick = ChunkCacheLimits.MAX_OFFERS_PER_TICK
        state.warmupEndsAt = nowMillis() + payload.durationMillis
        state.warmupBaseline = warmupCounters(state)
        state.warmupStartedNanos = System.nanoTime()
    }

    private fun warmupCounters(state: State) = longArrayOf(
        state.preparationAttempts, state.prepared, state.cacheAbsent, state.incompatible, state.preparationFailures,
        state.preparedTooLate, state.pinRejected, state.offersSent, state.absentReported, state.hits, state.offersRetired,
    )

    /** One line when warm-up ends: what the client decided in time, and where preparation waited. */
    private fun logWarmup(state: State, endedEarly: Boolean = false) {
        val baseline = state.warmupBaseline ?: return
        state.warmupBaseline = null
        val now = warmupCounters(state)
        val delta = LongArray(now.size) { now[it] - baseline[it] }
        val count = state.warmupLatencyCount.coerceAtLeast(1)
        logger.info(
            "CHUNK_REUSE_CLIENT_WARMUP ended_early={} attempts={} prepared={} absent={} incompatible={} prepare_failures={} too_late={} pin_rejected={} offers_sent={} absent_reported={} hits={} offers_retired={} first_offer_ms={} prepare_latency_avg_ms={} prepare_latency_max_ms={} main_hop_avg_ms={} main_hop_max_ms={}",
            endedEarly, delta[0], delta[1], delta[2], delta[3], delta[4], delta[5], delta[6], delta[7], delta[8], delta[9], delta[10],
            if (state.firstOfferNanos == 0L) -1.0 else (state.firstOfferNanos - state.warmupStartedNanos) / 1_000_000.0,
            state.warmupPrepareLatencyNanos / count / 1_000_000.0, state.warmupMaxPrepareLatencyNanos / 1_000_000.0,
            state.warmupMainHopNanos / count / 1_000_000.0, state.warmupMaxMainHopNanos / 1_000_000.0,
        )
    }

    fun retire(payload: ChunkCacheRetirePayload, listener: ClientPacketListener) {
        val state = state(listener) ?: return
        if (payload.epoch != state.epoch) return
        for (id in payload.ids) {
            val pin = state.pins.retire(id) ?: continue
            if (!pin.cancelling) state.offersRetired++
        }
        state.repairs.values.removeIf { it.id in payload.ids }
    }

    fun reuse(payload: ChunkCacheReusePayload, listener: ClientPacketListener) {
        val state = state(listener) ?: return
        if (payload.epoch != state.epoch || payload.dimension != state.level.dimension().location()) return
        val position = ChunkPos.asLong(payload.x, payload.z)
        val pin = state.pins.get(payload.id)
        val started = System.nanoTime()
        try {
            check(pin != null && pin.position == position && pin.hash.contentEquals(payload.hash)) {
                "Reuse response does not match a pinned terrain snapshot"
            }
            check(pin.value.minSection == state.level.minSection && pin.value.sectionCount == state.level.sectionsCount)
            val restoreStarted = System.nanoTime()
            val packet = try {
                ChunkCacheCompat.restoreReuse(payload.metadata, pin.value.sections, state.level)
            } finally {
                state.restoreNanos += System.nanoTime() - restoreStarted
            }
            check(packet.x == payload.x && packet.z == payload.z) { "Reuse metadata has different chunk coordinates" }
            // A synthetic packet needs the same detached base handoff that genuine full packets receive on Netty.
            val captureStarted = System.nanoTime()
            try {
                RdiChunkCache.captureBase(state.capture, packet.x, packet.z, packet.chunkData)
            } finally {
                state.captureNanos += System.nanoTime() - captureStarted
            }
            state.applyingReuse = true
            state.repairs.remove(position)
            val applyStarted = System.nanoTime()
            try {
                state.listener.handleLevelChunkWithLight(packet)
            } finally {
                state.applyingReuse = false
                state.applyNanos += System.nanoTime() - applyStarted
            }
            state.hits++
            state.received.add(position)
            send(state, ChunkCacheResultPayload(state.epoch, payload.id, payload.x, payload.z, payload.hash, true))
            state.pins.retire(payload.id)
        } catch (failure: Exception) {
            state.applyingReuse = false
            state.reuseFailures++
            logger.warn("Cannot restore cached terrain at {}, {}; requesting the issued full chunk", payload.x, payload.z, failure)
            state.repairs.put(position, Repair(payload.id, nowMillis()))
            state.pins.retire(payload.id)
            send(state, ChunkCacheResultPayload(state.epoch, payload.id, payload.x, payload.z, payload.hash, false))
        } finally {
            val elapsed = System.nanoTime() - started
            state.reuseNanos += elapsed
            state.maxReuseNanos = maxOf(state.maxReuseNanos, elapsed)
        }
    }

    @JvmStatic
    fun tick() {
        val state = current ?: return
        if (!active(state)) {
            close()
            return
        }
        val canPrepare = RdiChunkCache.isCacheActive(state.capture)
        if (!canPrepare) reportPause(state.listener, state.epoch, state.capture)
        if (!updateView(state)) return
        val now = nowMillis()
        cancel(state, state.pins.cancelWhere { pin ->
            val expired = now - pin.offeredAt >= ChunkCacheLimits.OFFER_TTL_MILLIS
            if (expired) state.offersExpired++
            !canPrepare || !near(state, pin.position) || expired || state.received.contains(pin.position)
        })
        if (state.repairs.values.any { now - it.startedAt > REPAIR_TIMEOUT_MILLIS }) {
            // The server repairs valid issued responses. A missing fence must not leave a permanent terrain hole.
            logger.error("Chunk cache repair did not complete within {}ms", REPAIR_TIMEOUT_MILLIS)
            ChunkCacheCompat.disconnect(state.listener, net.minecraft.network.chat.Component.literal("区块同步超时，请重新加入房间"))
            close()
            return
        }
        state.candidates.restart()
        pump(state, now)
        flush(state)
        if (state.warmupBaseline != null && !warmingUp(state, now)) logWarmup(state)
        if (System.nanoTime() - state.lastLog >= TimeUnit.SECONDS.toNanos(30)) {
            state.lastLog = System.nanoTime()
            logger.info("CHUNK_REUSE_CLIENT hits={} reuse_failures={} repairs_completed={} attempts={} prepared={} absent={} incompatible={} prepare_failures={} too_late={} outside_view={} pin_rejected={} offers_sent={} offers_expired={} offers_retired={} pins={} pin_bytes={} preparing={} repairs={}",
                state.hits, state.reuseFailures, state.repairsCompleted, state.preparationAttempts, state.prepared,
                state.cacheAbsent, state.incompatible, state.preparationFailures, state.preparedTooLate, state.preparedOutsideView,
                state.pinRejected, state.offersSent, state.offersExpired, state.offersRetired,
                state.pins.size, state.pins.bytes, state.preparing.size, state.repairs.size)
            logger.info("CHUNK_REUSE_CLIENT_TIME prepare_success_ms={} prepare_success_max_ms={} restore_ms={} capture_ms={} apply_ms={} reuse_ms={} reuse_max_ms={}",
                state.prepareNanos / 1_000_000.0, state.maxPrepareNanos / 1_000_000.0,
                state.restoreNanos / 1_000_000.0, state.captureNanos / 1_000_000.0,
                state.applyNanos / 1_000_000.0, state.reuseNanos / 1_000_000.0, state.maxReuseNanos / 1_000_000.0)
        }
    }

    private fun updateView(state: State): Boolean {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return false
        val center = player.chunkPosition()
        val viewDistance = ChunkCacheViewRange.viewDistance(ChunkCacheCompat.trackedChunkRadius(minecraft, state.listener))
        if (center.x != state.centerX || center.z != state.centerZ || viewDistance != state.viewDistance) {
            state.centerX = center.x
            state.centerZ = center.z
            state.viewDistance = viewDistance
            state.candidates.update(center.x, center.z, ChunkCacheViewRange.scanRadius(viewDistance))
            val retries = state.retryAfter.keys.iterator()
            while (retries.hasNext()) {
                if (!near(state, retries.nextLong())) retries.remove()
            }
        }
        return true
    }

    /** Fills worker slots without repeating tick maintenance; offers leave in [flush] at the end of the tick. */
    private fun pump(state: State, now: Long) {
        if (!active(state) || !RdiChunkCache.isCacheActive(state.capture) || !updateView(state)) return
        if (state.pins.size >= state.capacity || state.pins.bytes >= state.pins.maxBytes) return
        val maxPreparing = if (warmingUp(state, now)) WARMUP_MAX_PREPARING else MAX_PREPARING
        while (state.candidates.hasNext()) {
            if (state.preparing.size >= maxPreparing || outstandingPreparations.get() >= maxPreparing) break
            val position = state.candidates.next()
            if (!near(state, position) || state.received.contains(position) || state.pins.contains(position) ||
                state.preparing.contains(position) || state.repairs.containsKey(position) ||
                state.retryAfter.get(position) > now) continue
            prepare(state, position, now)
        }
    }

    /** Sends this tick's cancels, then this tick's offers and absent reports; see [ChunkCacheOutbox]. */
    private fun flush(state: State) {
        state.outbox.drain(
            ChunkCacheLimits.MAX_IDS_PER_MESSAGE,
            state.offersPerTick,
            { ids -> send(state, ChunkCacheCancelPayload(state.epoch, ids)) },
            { batch ->
                // A pin cancelled after queueing is settled by its cancel; never offer it.
                val offers = batch.filter { state.pins.get(it.id)?.cancelling == false }
                if (offers.isNotEmpty()) {
                    send(state, ChunkCacheOfferPayload(state.epoch, state.level.dimension().location(), offers))
                    state.offersSent += offers.size
                    if (state.firstOfferNanos == 0L) state.firstOfferNanos = System.nanoTime()
                }
            },
            ChunkCacheLimits.MAX_OFFER_BATCH,
        )
        if (state.absent.isEmpty()) return
        if (warmingUp(state, nowMillis())) {
            val dimension = state.level.dimension().location()
            state.absent.chunked(ChunkCacheLimits.MAX_ABSENT_BATCH).forEach { send(state, ChunkCacheAbsentPayload(state.epoch, dimension, it)) }
            state.absentReported += state.absent.size
        }
        state.absent.clear()
    }

    private fun warmingUp(state: State, now: Long): Boolean = now < state.warmupEndsAt

    private fun recordWarmupLatency(state: State, prepareNanos: Long, mainHopNanos: Long) {
        state.warmupLatencyCount++
        state.warmupPrepareLatencyNanos += prepareNanos
        state.warmupMaxPrepareLatencyNanos = maxOf(state.warmupMaxPrepareLatencyNanos, prepareNanos)
        state.warmupMainHopNanos += mainHopNanos
        state.warmupMaxMainHopNanos = maxOf(state.warmupMaxMainHopNanos, mainHopNanos)
    }

    /** Tells a warming-up server not to wait for this chunk. */
    private fun decline(state: State, position: Long) {
        if (warmingUp(state, nowMillis()) && near(state, position)) state.absent.add(position)
    }

    private fun prepare(state: State, position: Long, now: Long) {
        state.preparing.add(position)
        state.preparationAttempts++
        outstandingPreparations.incrementAndGet()
        state.retryAfter.put(position, now + RETRY_MILLIS)
        val x = ChunkPos.getX(position)
        val z = ChunkPos.getZ(position)
        val key = ClientChunkRegionSink.Key(state.level.dimension().location(), x, z)
        val biomes = state.level.registryAccess().registryOrThrow(Registries.BIOME)
        val minSection = state.level.minSection
        val sectionCount = state.level.sectionsCount
        val submittedNanos = System.nanoTime()
        RdiChunkCache.readCachedTerrain(state.capture, key).thenApplyAsync({ terrain ->
            when {
                state.closed -> null
                terrain == null -> Prepared(null, 0, missing = true)
                terrain.base.minSection() != minSection || terrain.base.sectionCount() != sectionCount -> Prepared(null, 0)
                else -> {
                    val started = System.nanoTime()
                    val ready = ChunkTerrainCodec.prepare(minSection, terrain.decodeSections(biomes), biomes)
                    Prepared(ready, System.nanoTime() - started)
                }
            }
        }, preparation).whenComplete { result, failure ->
            outstandingPreparations.decrementAndGet()
            val preparedNanos = System.nanoTime()
            // Always enqueue, including an already-failed read, so filling slots cannot recurse inline.
            Minecraft.getInstance().tell {
                state.preparing.remove(position)
                if (state.warmupBaseline != null) recordWarmupLatency(state, preparedNanos - submittedNanos, System.nanoTime() - preparedNanos)
                if (!active(state) || !RdiChunkCache.isCacheActive(state.capture) || !updateView(state)) return@tell
                try {
                    if (failure != null) {
                        state.preparationFailures++
                        logger.debug("Cached terrain unavailable at {}, {}", x, z, failure)
                        decline(state, position)
                        return@tell
                    }
                    if (result == null) return@tell
                    val terrain = result.terrain
                    if (terrain == null) {
                        if (result.missing) state.cacheAbsent++ else state.incompatible++
                        decline(state, position)
                        return@tell
                    }
                    state.prepareNanos += result.nanos
                    state.maxPrepareNanos = maxOf(state.maxPrepareNanos, result.nanos)
                    state.prepared++
                    if (!near(state, position)) {
                        state.preparedOutsideView++
                        return@tell
                    }
                    if (state.received.contains(position)) {
                        state.preparedTooLate++
                        return@tell
                    }
                    val pin = if (state.pins.size >= state.capacity) null else state.pins.add(position, terrain.hash, terrain,
                        terrain.sections.size.toLong() + 128, nowMillis())
                    if (pin == null) {
                        state.pinRejected++
                        decline(state, position)
                        return@tell
                    }
                    state.outbox.offer(ChunkCacheOffer(pin.id, x, z, pin.hash))
                } finally {
                    // Continue immediately from the cursor; restarting here repeatedly scans the same prefix.
                    pump(state, nowMillis())
                }
            }
        }
    }

    private data class Prepared(val terrain: PreparedTerrain?, val nanos: Long, val missing: Boolean = false)

    @JvmStatic
    fun fullReceived(listener: ClientPacketListener, x: Int, z: Int) {
        val state = current ?: return
        if (state.listener !== listener || state.applyingReuse) return
        val position = ChunkPos.asLong(x, z)
        state.received.add(position)
        if (state.repairs.remove(position) != null) state.repairsCompleted++
        cancel(state, state.pins.cancelWhere { it.position == position })
    }

    @JvmStatic
    fun forgotten(listener: ClientPacketListener, x: Int, z: Int) {
        val state = current ?: return
        if (state.listener !== listener) return
        val position = ChunkPos.asLong(x, z)
        state.received.remove(position)
        state.repairs.remove(position)
        state.retryAfter.remove(position)
        cancel(state, state.pins.cancelWhere { it.position == position })
    }

    @JvmStatic
    fun reset(listener: ClientPacketListener) {
        if (current?.listener === listener) close()
    }

    @JvmStatic
    fun waitingForRepair(level: ClientLevel, x: Int, z: Int): Boolean =
        current?.let { it.level === level && it.repairs.containsKey(ChunkPos.asLong(x, z)) } == true

    @JvmStatic
    fun shouldDropUpdate(listener: ClientPacketListener, packet: Packet<*>): Boolean {
        val state = current ?: return false
        if (state.listener !== listener || state.repairs.isEmpty()) return false
        return when (packet) {
            is ClientboundBlockUpdatePacket -> waitingForRepair(state.level, packet.pos.x shr 4, packet.pos.z shr 4)
            is ClientboundBlockEntityDataPacket -> waitingForRepair(state.level, packet.pos.x shr 4, packet.pos.z shr 4)
            is ClientboundLightUpdatePacket -> waitingForRepair(state.level, packet.x, packet.z)
            is ClientboundSectionBlocksUpdatePacket -> {
                var drop = false
                packet.runUpdates { pos, _ -> drop = waitingForRepair(state.level, pos.x shr 4, pos.z shr 4) }
                drop
            }
            else -> false
        }
    }

    @JvmStatic
    fun filterBiomes(listener: ClientPacketListener, packet: ClientboundChunksBiomesPacket): ClientboundChunksBiomesPacket {
        val state = current ?: return packet
        if (state.listener !== listener || state.repairs.isEmpty()) return packet
        val retained = packet.chunkBiomeData.filter { !waitingForRepair(state.level, it.pos.x, it.pos.z) }
        return if (retained.size == packet.chunkBiomeData.size) packet else ClientboundChunksBiomesPacket(retained)
    }

    /** Tells the server once per connection why this client stopped offering cached chunks, so its log shows it. */
    private fun reportPause(listener: ClientPacketListener, epoch: UUID, capture: RdiChunkCache.PacketCaptureContext) {
        if (!ChunkCacheCompat.canReportPause(listener)) return
        val reason = RdiChunkCache.claimPauseReason(capture) ?: return
        ChunkCacheCompat.sendToServer(listener, ChunkCachePausedPayload(epoch, reason.take(ChunkCacheLimits.MAX_PAUSE_REASON_CHARS)))
    }

    private fun state(listener: ClientPacketListener): State? = current?.takeIf {
        active(it) && listener === it.listener
    }

    private fun active(state: State): Boolean {
        val minecraft = Minecraft.getInstance()
        return current === state && !state.closed && minecraft.connection === state.listener && minecraft.level === state.level
    }

    /** The server's offer admission range, so corner candidates are not prepared only to be rejected. */
    private fun near(state: State, position: Long): Boolean =
        ChunkCacheViewRange.admits(ChunkPos.getX(position), ChunkPos.getZ(position), state.centerX, state.centerZ, state.viewDistance)

    /** Queues cancels for the end-of-tick flush; offers that never left are retired here without a message. */
    private fun cancel(state: State, ids: List<Long>) {
        if (ids.isEmpty()) return
        state.outbox.cancel(ids).forEach { state.pins.retire(it) }
    }

    private fun send(state: State, payload: ChunkCacheServerboundPayload) {
        if (active(state)) ChunkCacheCompat.sendToServer(state.listener, payload)
    }

    private fun nowMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())

    /** Prepared terrain stays in RAM until the server answers; small heaps get a smaller share. */
    private fun preparedBytesLimit(): Long =
        minOf(ChunkCacheLimits.MAX_PREPARED_BYTES.toLong(), Runtime.getRuntime().maxMemory() / 8).coerceAtLeast(16L * 1024 * 1024)

    private fun close() {
        current?.let { if (it.warmupBaseline != null) logWarmup(it, endedEarly = true) }
        current?.let { it.closed = true; it.pins.clear(); it.repairs.clear() }
        current = null
    }
}
