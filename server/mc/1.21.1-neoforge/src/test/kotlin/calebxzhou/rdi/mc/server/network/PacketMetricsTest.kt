package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.metrics.PacketDirection
import net.minecraft.network.protocol.common.CommonPacketTypes
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PacketMetricsTest {
    @Test
    fun `packet keys include direction and custom payload namespace and path`() {
        val clientKeepAlive = packetMetricKey(ClientboundKeepAlivePacket(1L), PacketDirection.S2C)
        val serverKeepAlive = packetMetricKey(ServerboundKeepAlivePacket(2L), PacketDirection.C2S)
        assertNotEquals(clientKeepAlive, serverKeepAlive)
        assertEquals("minecraft:keep_alive", clientKeepAlive.packetType)
        assertEquals("minecraft:keep_alive", serverKeepAlive.packetType)
        assertEquals(PacketDirection.S2C, clientKeepAlive.direction)
        assertEquals(PacketDirection.C2S, serverKeepAlive.direction)
        assertEquals(null, clientKeepAlive.namespace)
        assertEquals(null, clientKeepAlive.path)

        val serverPayload = packetMetricKey(
            ServerboundCustomPayloadPacket(payload("example", "same")),
            PacketDirection.C2S,
        )
        val differentPath = packetMetricKey(
            ServerboundCustomPayloadPacket(payload("example", "other")),
            PacketDirection.C2S,
        )
        val differentNamespace = packetMetricKey(
            ServerboundCustomPayloadPacket(payload("another", "same")),
            PacketDirection.C2S,
        )
        val emptyPath = packetMetricKey(
            ServerboundCustomPayloadPacket(payload("example", "")),
            PacketDirection.C2S,
        )

        assertEquals(
            CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD.id(),
            CommonPacketTypes.SERVERBOUND_CUSTOM_PAYLOAD.id(),
        )
        assertEquals("minecraft:custom_payload", serverPayload.packetType)
        assertEquals("example", serverPayload.namespace)
        assertEquals("same", serverPayload.path)
        assertNotEquals(serverPayload, differentPath)
        assertNotEquals(serverPayload, differentNamespace)
        assertEquals("", emptyPath.path)
        assertNotEquals(clientKeepAlive, emptyPath)
    }

    private fun payload(namespace: String, path: String): CustomPacketPayload {
        val id = ResourceLocation.fromNamespaceAndPath(namespace, path)
        return object : CustomPacketPayload {
            override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = CustomPacketPayload.Type(id)
        }
    }
}
