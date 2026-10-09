package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.ZstdInboundPreparation
import calebxzau.rdi.mc.zstdcodec.v21.RdiExtensionChannels21
import calebxzau.rdi.mc.zstdcodec.v21.ExtensionDecision21
import calebxzau.rdi.mc.zstdcodec.v21.ZstreamSettings21
import io.netty.util.AttributeKey
import net.minecraft.server.level.ServerPlayer
import org.apache.logging.log4j.LogManager

object RServerZstdStream {
    private val logger = LogManager.getLogger("rdi.network-extensions")
    private val settingsKey = AttributeKey.valueOf<ZstreamSettings21>("rdi.zstream.settings21")

    fun onPlayerJoined(player: ServerPlayer) {
        val connection = player.connection.connection
        val name = player.gameProfile.name
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
