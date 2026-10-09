package calebxzau.rdi.mc.zstdcodec.v21

import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.protocol.PacketFlow
import net.neoforged.neoforge.network.registration.ChannelAttributes
import net.neoforged.neoforge.network.registration.NetworkChannel
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup
import kotlin.test.*

class RdiExtensionChannels21Test {
    @Test
    fun `both sides announce packet references`() {
        assertTrue(RdiExtensionChannels21.REFERENCES in RdiExtensionChannels21.supportedChannels(client = false))
        assertTrue(RdiExtensionChannels21.REFERENCES in RdiExtensionChannels21.supportedChannels(client = true))
    }

    @Test
    fun `only negotiated PLAY channels arm extensions`() {
        assertNegotiation(RdiExtensionChannels21.STREAM, 1)
        assertNegotiation(RdiExtensionChannels21.REFERENCES, 4)
    }

    private fun assertNegotiation(id: net.minecraft.resources.ResourceLocation, expectedMask: Int) {
        val channel = EmbeddedChannel()
        val connection = Connection(PacketFlow.CLIENTBOUND)
        Connection::class.java.getDeclaredField("channel").apply { isAccessible = true }.set(connection, channel)
        try {
            assertFalse(RdiExtensionChannels21.isNegotiated(connection, id))
            ChannelAttributes.getOrCreateAdHocChannels(connection).add(id)
            ChannelAttributes.getOrCreateCommonChannels(connection, ConnectionProtocol.PLAY).add(id)
            assertFalse(RdiExtensionChannels21.isNegotiated(connection, id))
            ChannelAttributes.setPayloadSetup(connection, NetworkPayloadSetup(mapOf(
                ConnectionProtocol.CONFIGURATION to mapOf(id to NetworkChannel(id, "1")),
            )))
            assertEquals(0, RdiExtensionChannels21.inboundExtensions(connection))
            ChannelAttributes.setPayloadSetup(connection, NetworkPayloadSetup(mapOf(
                ConnectionProtocol.PLAY to mapOf(id to NetworkChannel(id, "1")),
            )))
            assertTrue(RdiExtensionChannels21.isNegotiated(connection, id))
            assertEquals(expectedMask, RdiExtensionChannels21.inboundExtensions(connection))
        } finally { channel.finishAndReleaseAll() }
    }
}
