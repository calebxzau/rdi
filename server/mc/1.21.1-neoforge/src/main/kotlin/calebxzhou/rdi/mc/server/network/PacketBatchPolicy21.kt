package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.zstdcodec.ZstdBatchPolicy
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.GamePacketTypes

/**
 * Only vanilla attribute updates and, with [classify]'s `entityBatching`, entity tracking updates may wait
 * for the tick-end flush. Every other packet, including all custom payloads, is an immediate barrier that
 * sends earlier buffered records first, so the wire order never changes. Inside a bundle the closing
 * delimiter is such a barrier.
 *
 * Matches the wire packet type rather than the class, so classification never initialises the
 * attribute packet class and its registry-backed codecs.
 */
internal object PacketBatchPolicy21 {
    /**
     * What `ServerEntity.sendChanges` broadcasts every few ticks for each tracked entity. These are mostly
     * below the compression floor and would otherwise leave as raw frames.
     */
    private val ENTITY_TRACKING = setOf(
        GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS,
        GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS_ROT,
        GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_ROT,
        GamePacketTypes.CLIENTBOUND_SET_ENTITY_MOTION,
        GamePacketTypes.CLIENTBOUND_ROTATE_HEAD,
        GamePacketTypes.CLIENTBOUND_SET_ENTITY_DATA,
        GamePacketTypes.CLIENTBOUND_TELEPORT_ENTITY,
    )

    fun classify(packet: Packet<*>, entityBatching: Boolean = false): ZstdBatchPolicy {
        val type = packet.type()
        return if (type == GamePacketTypes.CLIENTBOUND_UPDATE_ATTRIBUTES || entityBatching && type in ENTITY_TRACKING) {
            ZstdBatchPolicy.OneTick
        } else {
            ZstdBatchPolicy.Immediate
        }
    }
}
