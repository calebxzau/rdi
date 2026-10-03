package calebxzau.rdi.mc.v20.server.attributes

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket
import net.minecraft.world.entity.ai.attributes.Attribute

/** One play connection's attribute cache and counters. All access holds this object's monitor. */
class AttributeDedupState internal constructor(
    val enabled: Boolean,
    refreshNanos: Long,
    maxEntities: Int,
) {
    internal val cache = AttributeSyncCache<Attribute>(refreshNanos, maxEntities)

    /** Set only on the server thread while a trimmed packet is re-sent. */
    @JvmField
    var forwarding = false

    var packets = 0L
        internal set
    var cancelled = 0L
        internal set
    var trimmed = 0L
        internal set
    var skippedSnapshots = 0L
        internal set
    var offThreadResets = 0L
        internal set
}

/**
 * Drops 1.20.1 attribute snapshots that the client already holds. The client replaces an attribute's
 * base value and modifiers from each snapshot, so resending an identical snapshot changes nothing.
 * Entity pairing data inside bundles is never filtered.
 */
object AttributeDedup20 {
    const val MAX_ENTITIES = 4096
    private const val DEFAULT_REFRESH_MILLIS = 1000L

    @JvmStatic
    fun newState(): AttributeDedupState {
        val refreshMillis = System.getProperty("rdi.attrDedup.refreshMillis")?.toLongOrNull()
            ?.coerceAtLeast(0L) ?: DEFAULT_REFRESH_MILLIS
        return newState(
            System.getProperty("rdi.attrDedup.enabled", "true").toBoolean(),
            refreshMillis * 1_000_000L,
        )
    }

    internal fun newState(enabled: Boolean, refreshNanos: Long, maxEntities: Int = MAX_ENTITIES) =
        AttributeDedupState(enabled, refreshNanos, maxEntities)

    /**
     * Returns [packet] unchanged, a trimmed replacement, or null when nothing needs sending. Packets
     * with a send listener are never replaced, and off-thread attribute sends reset the cache.
     */
    @JvmStatic
    fun process(state: AttributeDedupState, packet: Packet<*>, hasListener: Boolean, serverThread: Boolean): Packet<*>? =
        process(state, packet, hasListener, serverThread, System.nanoTime())

    internal fun process(
        state: AttributeDedupState,
        packet: Packet<*>,
        hasListener: Boolean,
        serverThread: Boolean,
        nowNanos: Long,
    ): Packet<*>? {
        if (!state.enabled || !isRelevant(packet)) return packet
        synchronized(state) {
            if (packet !is ClientboundUpdateAttributesPacket || hasListener || !serverThread) {
                observe(state, packet, serverThread, nowNanos)
                return packet
            }
            state.packets++
            val values = snapshots(packet) ?: run {
                state.cache.forget(packet.entityId)
                return packet
            }
            val keep = state.cache.filter(packet.entityId, values, nowNanos)
            val kept = keep.count { it }
            if (kept == values.size) return packet
            state.skippedSnapshots += values.size - kept
            if (kept == 0) {
                state.cancelled++
                return null
            }
            state.trimmed++
            val trimmedPacket = ClientboundUpdateAttributesPacket(packet.entityId, emptyList())
            packet.values.filterIndexedTo(trimmedPacket.values) { index, _ -> keep[index] }
            return trimmedPacket
        }
    }

    private fun isRelevant(packet: Packet<*>): Boolean = packet is ClientboundUpdateAttributesPacket ||
        packet is ClientboundBundlePacket || packet is ClientboundRemoveEntitiesPacket ||
        packet is ClientboundAddEntityPacket || packet is ClientboundAddPlayerPacket ||
        packet is ClientboundRespawnPacket || packet is ClientboundLoginPacket

    /** Applies a packet that is sent as-is, in order, including bundled pairing data. */
    private fun observe(state: AttributeDedupState, packet: Packet<*>, serverThread: Boolean, nowNanos: Long) {
        val cache = state.cache
        when (packet) {
            is ClientboundUpdateAttributesPacket -> {
                val values = if (serverThread) snapshots(packet) else null
                when {
                    !serverThread -> {
                        // Ordering against server-thread sends is unknown, so no cached state is trusted.
                        cache.clear()
                        state.offThreadResets++
                    }
                    values == null -> cache.forget(packet.entityId)
                    else -> cache.record(packet.entityId, values, nowNanos)
                }
            }
            is ClientboundBundlePacket -> for (subPacket in packet.subPackets()) observe(state, subPacket, serverThread, nowNanos)
            is ClientboundRemoveEntitiesPacket -> {
                val entityIds = packet.entityIds
                for (index in 0 until entityIds.size) cache.forget(entityIds.getInt(index))
            }
            is ClientboundAddEntityPacket -> cache.forget(packet.id)
            is ClientboundAddPlayerPacket -> cache.forget(packet.entityId)
            is ClientboundRespawnPacket, is ClientboundLoginPacket -> cache.clear()
        }
    }

    /** Null when an attribute is unregistered; such packets pass through uncached. */
    private fun snapshots(packet: ClientboundUpdateAttributesPacket): List<Pair<Attribute, AttributeSyncValue>>? =
        packet.values.map { snapshot ->
            val attribute: Attribute = snapshot.attribute ?: return null
            attribute to AttributeSyncValue(
                snapshot.base.toRawBits(),
                snapshot.modifiers.map { modifier ->
                    AttributeSyncValue.Modifier(modifier.id, modifier.amount.toRawBits(), modifier.operation.toValue())
                },
            )
        }
}
