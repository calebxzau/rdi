package calebxzau.rdi.mc.v20.server.network

import calebxzau.rdi.mc.zstdcodec.ZstdBatchPolicy
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
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

    @Test
    fun `entity tracking updates batch for one tick only when enabled`(): Unit {
        val packets = listOf(
            ClientboundMoveEntityPacket.Pos(7, 1, 2, 3, true),
            ClientboundMoveEntityPacket.Rot(7, 10, 20, false),
            ClientboundMoveEntityPacket.PosRot(7, 1, 2, 3, 10, 20, true),
            ClientboundSetEntityMotionPacket(7, Vec3(0.5, 0.0, -0.5)),
            ClientboundSetEntityDataPacket(7, emptyList()),
            decode(::ClientboundRotateHeadPacket) { it.writeVarInt(7); it.writeByte(30) },
            decode(::ClientboundTeleportEntityPacket) {
                it.writeVarInt(7)
                it.writeDouble(1.0); it.writeDouble(64.0); it.writeDouble(-1.0)
                it.writeByte(0); it.writeByte(0); it.writeBoolean(true)
            },
        )
        val encoded = Unpooled.buffer()
        try {
            for (packet in packets) {
                assertEquals(ZstdBatchPolicy.OneTick,
                    PacketBatchPolicy20.classify(packet, encoded, emptySet(), longWindow = true, entityBatching = true))
                assertEquals(ZstdBatchPolicy.Immediate,
                    PacketBatchPolicy20.classify(packet, encoded, emptySet(), longWindow = false, entityBatching = false))
            }
            assertEquals(ZstdBatchPolicy.Immediate, PacketBatchPolicy20.classify(
                ClientboundKeepAlivePacket(1), encoded, emptySet(), longWindow = false, entityBatching = true))
        } finally {
            encoded.release()
        }
    }

    private fun <T> decode(reader: (FriendlyByteBuf) -> T, writer: (FriendlyByteBuf) -> Unit): T {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            writer(buffer)
            return reader(buffer)
        } finally {
            buffer.release()
        }
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
                if (verified) setOf(channel) else emptySet(), longWindow, entityBatching = true))
            assertEquals(readerIndex, encoded.readerIndex())
            assertEquals(dataIndex, data.readerIndex())
        } finally {
            data.release()
            encoded.release()
        }
    }
}
