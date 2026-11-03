package calebxzau.rdi.mc.zstdcodec.v21

import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.ZstdInboundPreparation
import io.netty.util.AttributeKey
import net.minecraft.network.Connection
import org.apache.logging.log4j.LogManager

/** Moves a player's outbound packets onto one shared zstd stream once the PLAY channels negotiated it. */
object ZstdStreamActivation21 {
    private val logger = LogManager.getLogger("rdi.network-extensions")
    private val settingsKey = AttributeKey.valueOf<ZstreamSettings21>("rdi.zstream.settings21")

    /** Call after the player joined; memory connections and peers without the extension keep independent frames. */
    fun activate(connection: Connection, name: String) {
        val channel = connection.channel()
        val attribute = channel.attr(settingsKey)
        val settings = attribute.get() ?: ZstreamSettings21.read(System::getProperty) { logger.warn(it) }
            .let { attribute.setIfAbsent(it) ?: it }
        channel.eventLoop().execute {
            if (!channel.isActive) return@execute
            try {
                if (ZstdCompressionPipeline.isOutboundStreamEnabled(channel)) return@execute
                val decision = settings.decide(
                    connection.isMemoryConnection,
                    RdiExtensionChannels21.isNegotiated(connection, RdiExtensionChannels21.STREAM),
                    ZstdCompressionPipeline.hasCompressionEncoder(channel),
                    ZstdCompressionPipeline.hasOutboundEncoder(channel),
                )
                if (decision == ExtensionDecision21.Enable) {
                    ZstdCompressionPipeline.setOutboundStream(channel, settings.windowLog)
                    check(ZstdCompressionPipeline.isOutboundStreamEnabled(channel)) { "Zstd stream activation failed" }
                    logger.info("Player {} Zstd stream active, windowLog={} ({} MiB per side)",
                        name, settings.windowLog, 1 shl (settings.windowLog - 20))
                } else {
                    logger.info("Player {} keeps independent frames: {}, handlers={}", name, decision,
                        ZstdCompressionPipeline.describeHandlers(channel))
                }
            } catch (error: Exception) {
                logger.error("Unable to activate Zstd stream for ${name}", error)
                ZstdInboundPreparation.abort(channel, error)
            }
        }
    }
}
