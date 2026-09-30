package calebxzau.rdi.mc.v20.server.network

import calebxzau.rdi.mc.zstdcodec.ZstdBatchPolicy
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket
import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals

class PacketBatchPolicy20Test {
    @Test
    fun `verified attribute name sync respects switch and preserves read index`(): Unit {
        check("l2tabs:main", 1, true, false, ZstdBatchPolicy.OneTick)
        check("l2tabs:main", 1, true, true, ZstdBatchPolicy.FourTicks)
        check("l2tabs:main", 1, false, true, ZstdBatchPolicy.Immediate)
    }

    @Test
    fun `open curios unknown ids other channels and truncated payloads send immediately`(): Unit {
        check("l2tabs:main", 0, true, true, ZstdBatchPolicy.Immediate)
        check("l2tabs:main", 255, true, true, ZstdBatchPolicy.Immediate)
        check("elementalcombat:main", 3, true, true, ZstdBatchPolicy.Immediate)
        check("l2tabs:main", null, true, true, ZstdBatchPolicy.Immediate)
    }

    private fun check(channel: String, id: Int?, verified: Boolean, longWindow: Boolean, expected: ZstdBatchPolicy) {
        val data = FriendlyByteBuf(Unpooled.buffer())
        val encoded = FriendlyByteBuf(Unpooled.buffer())
        try {
            if (id != null) data.writeByte(id)
            val packet = ClientboundCustomPayloadPacket(ResourceLocation(channel), data)
            encoded.writeVarInt(42)
            packet.write(encoded)
            val readerIndex = encoded.readerIndex()
            val dataIndex = data.readerIndex()
            assertEquals(expected, PacketBatchPolicy20.classify(packet, encoded,
                if (verified) setOf(channel) else emptySet(), longWindow))
            assertEquals(readerIndex, encoded.readerIndex())
            assertEquals(dataIndex, data.readerIndex())
        } finally {
            data.release()
            encoded.release()
        }
    }
}
