package calebxzhou.rdi.mc.client.network

import calebxzau.mc.common2021.RdiBatchChannel
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import net.minecraft.network.Connection
import org.apache.logging.log4j.LogManager

/**
 * Client side of the Zstd batching feature.
 *
 * The client accepts batch blocks only from a server that announced the batch channel, which mirrors
 * the check the server performs before it starts sending them.
 */
object RClientBatching {
    private val logger = LogManager.getLogger("RDI Client Batching")

    /** Enables batch decoding before the login packet is scheduled to the client thread. */
    fun onLoginPacket(connection: Connection) {
        if (connection.isMemoryConnection) {
            return
        }
        if (!RdiBatchChannel.isRemotePresent(connection)) {
            return
        }

        val channel = connection.channel()
        if (channel.pipeline().get("decompress") == null) {
            return
        }

        if (!ZstdCompressionPipeline.setInboundBatchingIfAvailable(channel, true)) {
            val error = IllegalStateException(
                "The server negotiated rdi:batch, but the inbound compression decoder is unavailable: " +
                    ZstdCompressionPipeline.describeHandlers(channel),
            )
            logger.error("Unable to enable negotiated packet batching before login", error)
            throw error
        }
    }

    /** Kept as an idempotent fallback for the later Forge login event. */
    fun onJoin(connection: Connection) {
        onLoginPacket(connection)
        logger.info("Login compression handlers: {}", ZstdCompressionPipeline.describeHandlers(connection.channel()))
    }

    /** Stops accepting batch blocks when the session ends. */
    fun onLeave(connection: Connection?) {
        if (connection == null) {
            return
        }
        ZstdCompressionPipeline.setInboundBatching(connection.channel(), false)
    }
}
