package calebxzhou.rdi.mc.client.network

import calebxzau.mc.common2021.RdiZstdStreamChannel
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import net.minecraft.network.Connection
import org.apache.logging.log4j.LogManager

/**
 * Client side of the Zstd stream extension.
 *
 * The client only prepares its decoder here. The stream takes effect once the server's STREAM_START
 * frame arrives, which a server without the extension, or with it switched off, never sends.
 */
object RClientZstdStream {
    private val logger = LogManager.getLogger("RDI Client Zstd Stream")

    /** Prepares the decoder before the login packet is scheduled to the client thread. */
    fun onLoginPacket(connection: Connection) {
        if (connection.isMemoryConnection) {
            return
        }
        if (!RdiZstdStreamChannel.isRemotePresent(connection)) {
            return
        }

        val channel = connection.channel()
        if (channel.pipeline().get("decompress") == null) {
            return
        }

        if (!ZstdCompressionPipeline.setInboundStreamIfAvailable(channel, true)) {
            val error = IllegalStateException(
                "The server negotiated rdi:zstream, but the inbound compression decoder is unavailable: " +
                    ZstdCompressionPipeline.describeHandlers(channel),
            )
            logger.error("Unable to prepare the negotiated Zstd stream before login", error)
            throw error
        }
    }

    /** Idempotent fallback for the later Forge login event; it never disturbs a running stream. */
    fun onJoin(connection: Connection) {
        onLoginPacket(connection)
    }

    /** Forgets the stream when the session ends. */
    fun onLeave(connection: Connection?) {
        if (connection == null) {
            return
        }
        ZstdCompressionPipeline.setInboundStreamIfAvailable(connection.channel(), false)
    }
}
