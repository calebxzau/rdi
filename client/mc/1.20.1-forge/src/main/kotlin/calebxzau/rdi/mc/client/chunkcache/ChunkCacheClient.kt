package calebxzau.rdi.mc.client.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import calebxzau.rdi.mc.chunkcache.ChunkReuseCodec
import calebxzau.rdi.mc.chunkcache.ChunkTerrainCodec
import calebxzau.rdi.mc.chunkcache.ChunkTerrainCodec.PreparedTerrain
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheCancelPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheChannel
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheContextPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheOffer
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheOfferPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCachePayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheResultPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheRetirePayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheReusePayload
import calebxzau.rdi.mc.client.chunkcache.mixin.AChunkCacheServerChunkRadius
import com.mojang.logging.LogUtils
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
 * Forge 1.20.1 port of the 1.21 client: messages arrive through [ChunkCacheChannel] on the game thread,
 * so the current connection identifies the listener instead of a payload context.
 */
object ChunkCacheClient {
    private val logger = LogUtils.getLogger()
    private val preparation = Executors.newSingleThreadExecutor { task ->
        Thread(task, "rdi-chunk-cache-prepare").apply { isDaemon = true }
    }
    private const val MAX_PREPARING = 2
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
        val pins = ChunkCacheOfferPins<PreparedTerrain>(ChunkCacheLimits.MAX_OFFERS, ChunkCacheLimits.MAX_PREPARED_BYTES.toLong())
        val preparing = LongOpenHashSet()
        val retryAfter = Long2LongOpenHashMap()
        val pendingOffers = ArrayList<ChunkCacheOffer>()
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

    fun context(payload: ChunkCacheContextPayload) {
        val minecraft = Minecraft.getInstance()
        val listener = minecraft.connection ?: return
        val level = minecraft.level ?: return
        if (minecraft.connection !== listener || level.dimension().location() != payload.dimension) return
        close()
        val capture = (listener as? ChunkCachePacketAccess)?.`rdi$cacheContext`() ?: return
        if (!RdiChunkCache.isCacheActive(capture) || minecraft.hasSingleplayerServer()) return
        current = State(listener, level, capture, payload.epoch)
    }

    fun retire(payload: ChunkCacheRetirePayload) {
        val state = state() ?: return
        if (payload.epoch != state.epoch) return
        payload.ids.forEach { state.pins.retire(it) }
        state.repairs.values.removeIf { it.id in payload.ids }
    }

    fun reuse(payload: ChunkCacheReusePayload) {
        val state = state() ?: return
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
                ChunkReuseCodec.restore(payload.metadata, pin.value.sections)
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
        if (!updateView(state)) return
        val now = nowMillis()
        cancel(state, state.pins.cancelWhere { pin ->
            val expired = now - pin.offeredAt >= ChunkCacheLimits.OFFER_TTL_MILLIS
            if (expired) state.offersExpired++
            !canPrepare || !near(state, pin.position) || expired ||
                state.level.chunkSource.hasChunk(ChunkPos.getX(pin.position), ChunkPos.getZ(pin.position))
        })
        if (state.repairs.values.any { now - it.startedAt > REPAIR_TIMEOUT_MILLIS }) {
            // The server repairs valid issued responses. A missing fence must not leave a permanent terrain hole.
            logger.error("Chunk cache repair did not complete within {}ms", REPAIR_TIMEOUT_MILLIS)
            state.listener.connection.disconnect(net.minecraft.network.chat.Component.literal("区块同步超时，请重新加入房间"))
            close()
            return
        }
        state.candidates.restart()
        pump(state, now)
        if (System.nanoTime() - state.lastLog >= TimeUnit.SECONDS.toNanos(30)) {
            state.lastLog = System.nanoTime()
            logger.info("CHUNK_REUSE_CLIENT hits={} reuse_failures={} repairs_completed={} attempts={} prepared={} absent={} incompatible={} prepare_failures={} too_late={} outside_view={} pin_rejected={} offers_sent={} offers_expired={} pins={} pin_bytes={} preparing={} repairs={}",
                state.hits, state.reuseFailures, state.repairsCompleted, state.preparationAttempts, state.prepared,
                state.cacheAbsent, state.incompatible, state.preparationFailures, state.preparedTooLate, state.preparedOutsideView,
                state.pinRejected, state.offersSent, state.offersExpired,
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
        // Not the render distance: a 1.20.1 server sends its whole view distance to every client.
        val viewDistance = ChunkCacheViewRange.viewDistance(
            (state.listener as AChunkCacheServerChunkRadius).`rdi$serverChunkRadius`(),
        )
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

    /** Publishes completed offers and fills worker slots, without repeating tick maintenance. */
    private fun pump(state: State, now: Long) {
        if (!active(state) || !RdiChunkCache.isCacheActive(state.capture) || !updateView(state)) return
        if (state.pendingOffers.isNotEmpty()) publishOffers(state)
        if (state.pins.size >= ChunkCacheLimits.MAX_OFFERS || state.pins.bytes >= ChunkCacheLimits.MAX_PREPARED_BYTES) return
        while (state.candidates.hasNext()) {
            if (state.preparing.size >= MAX_PREPARING || outstandingPreparations.get() >= MAX_PREPARING) break
            val position = state.candidates.next()
            val x = ChunkPos.getX(position)
            val z = ChunkPos.getZ(position)
            if (!near(state, position) || state.level.chunkSource.hasChunk(x, z) || state.pins.contains(position) ||
                state.preparing.contains(position) || state.repairs.containsKey(position) ||
                state.retryAfter.get(position) > now) continue
            prepare(state, position, now)
        }
    }

    private fun publishOffers(state: State) {
        var offers = ArrayList<ChunkCacheOffer>(ChunkCacheLimits.MAX_OFFER_BATCH)
        for (offer in state.pendingOffers) {
            if (state.pins.get(offer.id)?.cancelling != false) continue
            offers.add(offer)
            if (offers.size == ChunkCacheLimits.MAX_OFFER_BATCH) {
                send(state, ChunkCacheOfferPayload(state.epoch, state.level.dimension().location(), offers))
                state.offersSent += offers.size
                offers = ArrayList(ChunkCacheLimits.MAX_OFFER_BATCH)
            }
        }
        if (offers.isNotEmpty()) {
            send(state, ChunkCacheOfferPayload(state.epoch, state.level.dimension().location(), offers))
            state.offersSent += offers.size
        }
        state.pendingOffers.clear()
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
            // Always enqueue, including an already-failed read, so filling slots cannot recurse inline.
            Minecraft.getInstance().tell {
                state.preparing.remove(position)
                if (!active(state) || !RdiChunkCache.isCacheActive(state.capture) || !updateView(state)) return@tell
                try {
                    if (failure != null) {
                        state.preparationFailures++
                        logger.debug("Cached terrain unavailable at {}, {}", x, z, failure)
                        return@tell
                    }
                    if (result == null) return@tell
                    val terrain = result.terrain
                    if (terrain == null) {
                        if (result.missing) state.cacheAbsent++ else state.incompatible++
                        return@tell
                    }
                    state.prepareNanos += result.nanos
                    state.maxPrepareNanos = maxOf(state.maxPrepareNanos, result.nanos)
                    state.prepared++
                    if (!near(state, position)) {
                        state.preparedOutsideView++
                        return@tell
                    }
                    if (state.level.chunkSource.hasChunk(x, z)) {
                        state.preparedTooLate++
                        return@tell
                    }
                    val pin = state.pins.add(position, terrain.hash, terrain,
                        terrain.sections.size.toLong() + 128, nowMillis())
                    if (pin == null) {
                        state.pinRejected++
                        return@tell
                    }
                    state.pendingOffers.add(ChunkCacheOffer(pin.id, x, z, pin.hash))
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
        if (state.repairs.remove(position) != null) state.repairsCompleted++
        cancel(state, state.pins.cancelWhere { it.position == position })
    }

    @JvmStatic
    fun forgotten(listener: ClientPacketListener, x: Int, z: Int) {
        val state = current ?: return
        if (state.listener !== listener) return
        val position = ChunkPos.asLong(x, z)
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

    private fun state(): State? = current?.takeIf(::active)

    private fun active(state: State): Boolean {
        val minecraft = Minecraft.getInstance()
        return current === state && !state.closed && minecraft.connection === state.listener && minecraft.level === state.level
    }

    /** The server's offer admission range, so corner candidates are not prepared only to be rejected. */
    private fun near(state: State, position: Long): Boolean =
        ChunkCacheViewRange.admits(ChunkPos.getX(position), ChunkPos.getZ(position), state.centerX, state.centerZ, state.viewDistance)

    private fun cancel(state: State, ids: List<Long>) {
        ids.chunked(ChunkCacheLimits.MAX_OFFERS).forEach { send(state, ChunkCacheCancelPayload(state.epoch, it)) }
    }

    private fun send(state: State, payload: ChunkCachePayload) {
        if (active(state)) ChunkCacheChannel.sendToServer(payload)
    }

    private fun nowMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())

    private fun close() {
        current?.let { it.closed = true; it.pins.clear(); it.repairs.clear() }
        current = null
    }
}
