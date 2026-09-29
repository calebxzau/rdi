package calebxzau.rdi.mc.v20.server.network

import calebxzau.rdi.mc.metrics.PacketDirection
import calebxzau.rdi.mc.metrics.PacketMetricKey
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket
import net.minecraft.network.protocol.game.ServerGamePacketListener
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.game.ServerboundKeepAlivePacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket
import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class PacketMetrics20Test {
    @Test
    fun `vanilla packets keep their build time Mojang names`(): Unit {
        assertEquals(
            "net.minecraft.network.protocol.game.ServerboundKeepAlivePacket",
            PacketMetrics20.resolveName(ServerboundKeepAlivePacket::class.java),
        )
        assertEquals(
            "net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket",
            PacketMetrics20.resolveName(ClientboundCustomPayloadPacket::class.java),
        )
        assertEquals(
            "net.minecraft.network.protocol.game.ServerboundMovePlayerPacket\$Pos",
            PacketMetrics20.resolveName(ServerboundMovePlayerPacket.Pos::class.java),
        )
    }

    @Test
    fun `packet classes outside the generated table fall back to the runtime class name`(): Unit {
        val key = packetMetricKey(ForeignPacket(), PacketDirection.C2S)

        assertEquals(ForeignPacket::class.java.name, key.packetType)
        assertEquals(PacketDirection.C2S, key.direction)
        assertNull(key.namespace)
        assertNull(key.path)
        assertEquals("java.lang.String", PacketMetrics20.resolveName(String::class.java))
    }

    @Test
    fun `direction separates the key of one packet class`(): Unit {
        val s2c = packetMetricKey(ServerboundKeepAlivePacket(1L), PacketDirection.S2C)
        val c2s = packetMetricKey(ServerboundKeepAlivePacket(1L), PacketDirection.C2S)

        assertEquals(s2c.packetType, c2s.packetType)
        assertEquals(PacketDirection.S2C, s2c.direction)
        assertEquals(PacketDirection.C2S, c2s.direction)
        assertNotEquals(s2c, c2s)
    }

    @Test
    fun `custom payload packets carry their channel and stay separate per channel`(): Unit {
        val same = packetMetricKey(payloadPacket("example", "same"), PacketDirection.C2S)
        val otherPath = packetMetricKey(payloadPacket("example", "other"), PacketDirection.C2S)
        val otherNamespace = packetMetricKey(payloadPacket("another", "same"), PacketDirection.C2S)
        val emptyPath = packetMetricKey(payloadPacket("example", ""), PacketDirection.C2S)

        assertEquals("net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket", same.packetType)
        assertEquals("example", same.namespace)
        assertEquals("same", same.path)
        assertNotEquals(same, otherPath)
        assertNotEquals(same, otherNamespace)
        assertEquals("", emptyPath.path)
        assertNotEquals(same, emptyPath)
    }

    @Test
    fun `login query packets expose their channel while channel less packets stay null`(): Unit {
        val query = packetMetricKey(
            ClientboundCustomQueryPacket(
                7,
                ResourceLocation("rdi", "handshake"),
                FriendlyByteBuf(Unpooled.buffer()),
            ),
            PacketDirection.S2C,
        )

        assertEquals("net.minecraft.network.protocol.login.ClientboundCustomQueryPacket", query.packetType)
        assertEquals("rdi", query.namespace)
        assertEquals("handshake", query.path)

        val keepAlive = packetMetricKey(ClientboundKeepAlivePacket(1L), PacketDirection.S2C)
        assertNull(keepAlive.namespace)
        assertNull(keepAlive.path)
    }

    private fun packetMetricKey(packet: Packet<*>, direction: PacketDirection): PacketMetricKey =
        PacketMetrics20.metricKey(packet, direction)

    private fun payloadPacket(namespace: String, path: String): ServerboundCustomPayloadPacket =
        ServerboundCustomPayloadPacket(
            ResourceLocation(namespace, path),
            FriendlyByteBuf(Unpooled.buffer()),
        )

    /** Stands in for a mod packet class that the generated table cannot know about. */
    private class ForeignPacket : Packet<ServerGamePacketListener> {
        override fun write(buffer: FriendlyByteBuf) {
            throw UnsupportedOperationException("the test double is never serialized")
        }

        override fun handle(handler: ServerGamePacketListener) {
            throw UnsupportedOperationException("the test double is never handled")
        }
    }
}
