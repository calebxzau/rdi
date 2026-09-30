package calebxzau.rdi.mc.v20.server.network

import calebxzau.rdi.mc.zstdcodec.ZstdBatchPolicy
import io.netty.buffer.ByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket
import java.nio.charset.StandardCharsets

/** Conservative message whitelist, verified against the target pack's actual handlers. */
object PacketBatchPolicy20 {
    const val L2_TABS_CHANNEL = "l2tabs:main"
    const val L2_TABS_VERSION = "0.3.3"

    internal fun classify(
        packet: Packet<*>,
        encoded: ByteBuf,
        verifiedChannels: Set<String>,
        longWindow: Boolean,
    ): ZstdBatchPolicy {
        if (packet is ClientboundUpdateAttributesPacket) return ZstdBatchPolicy.OneTick
        if (packet !is ClientboundCustomPayloadPacket) return ZstdBatchPolicy.Immediate
        val channel = packet.identifier.toString()
        if (channel != L2_TABS_CHANNEL || channel !in verifiedChannels) return ZstdBatchPolicy.Immediate
        // The successful vanilla encoding is packet-id VarInt, channel UTF8, then payload.
        // Forge IndexedMessageCodec uses a single unsigned byte for its discriminator.
        val cursor = Cursor(encoded)
        if (cursor.varInt() == null) return ZstdBatchPolicy.Immediate
        val channelBytes = channel.toByteArray(StandardCharsets.UTF_8)
        if (cursor.varInt() != channelBytes.size) return ZstdBatchPolicy.Immediate
        for (value in channelBytes) {
            if (cursor.byte() != (value.toInt() and 255)) return ZstdBatchPolicy.Immediate
        }
        if (cursor.byte() != 0) return ZstdBatchPolicy.Immediate
        // ID0 SyncAttributeToClient updates name suppliers only. Entity value/skill/combat
        // updates and unknown messages remain ordering barriers.
        return if (longWindow) ZstdBatchPolicy.FourTicks else ZstdBatchPolicy.OneTick
    }

    private class Cursor(val buffer: ByteBuf) {
        var index = buffer.readerIndex()

        fun byte(): Int? = if (index < buffer.writerIndex()) buffer.getUnsignedByte(index++).toInt() else null

        fun varInt(): Int? {
            var value = 0
            for (shift in 0..28 step 7) {
                val next = byte() ?: return null
                if (shift == 28 && next and 240 != 0) return null
                value = value or ((next and 127) shl shift)
                if (next and 128 == 0) return value
            }
            return null
        }
    }
}
