package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.zstdcodec.ZstdBatchPolicy
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.GamePacketTypes

/**
 * Only vanilla attribute updates may wait for the tick-end flush. Every other packet, including all
 * custom payloads, is an immediate barrier that sends earlier buffered attributes first, so the wire
 * order never changes. Inside a bundle the closing delimiter is such a barrier.
 *
 * Matches the wire packet type rather than the class, so classification never initialises the
 * attribute packet class and its registry-backed codecs.
 */
internal object PacketBatchPolicy21 {
    fun classify(packet: Packet<*>): ZstdBatchPolicy =
        if (packet.type() == GamePacketTypes.CLIENTBOUND_UPDATE_ATTRIBUTES) ZstdBatchPolicy.OneTick
        else ZstdBatchPolicy.Immediate
}
