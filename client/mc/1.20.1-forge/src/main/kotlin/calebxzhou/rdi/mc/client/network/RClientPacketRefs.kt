package calebxzhou.rdi.mc.client.network

import calebxzau.mc.common2021.RdiPacketRefChannel
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import net.minecraft.network.Connection
import org.apache.logging.log4j.LogManager

/**
 * Client side of the packet reference extension.
 *
 * The client only prepares its decoder here. References take effect once the server's START frame
 * arrives, which a server without the extension, or with it switched off, never sends.
 */
object RClientPacketRefs {
    private val logger = LogManager.getLogger("RDI Client Packet Refs")

    /** Prepares the decoder before the login packet is scheduled to the client thread. */
    fun onLoginPacket(connection: Connection) {
        if (connection.isMemoryConnection) {
            return
        }
        if (!RdiPacketRefChannel.isRemotePresent(connection)) {
            return
        }

        val channel = connection.channel()
        if (channel.pipeline().get("decompress") == null) {
            return
        }

        if (!ZstdCompressionPipeline.setInboundPacketRefsIfAvailable(channel, true)) {
            val error = IllegalStateException(
                "The server negotiated rdi:pktref, but the inbound compression decoder is unavailable: " +
                    ZstdCompressionPipeline.describeHandlers(channel),
            )
            logger.error("Unable to prepare negotiated packet references before login", error)
            throw error
        }
    }

    /** Idempotent fallback for the later Forge login event; it never resets a table START aligned. */
    fun onJoin(connection: Connection) {
        onLoginPacket(connection)
    }

    /** Forgets the extension's table when the session ends. */
    fun onLeave(connection: Connection?) {
        if (connection == null) {
            return
        }
        ZstdCompressionPipeline.setInboundPacketRefsIfAvailable(connection.channel(), false)
    }
}
