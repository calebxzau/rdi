package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.ZstdInboundPreparation
import calebxzau.rdi.mc.zstdcodec.v21.BatchSettings21
import calebxzau.rdi.mc.zstdcodec.v21.ExtensionDecision21
import calebxzau.rdi.mc.zstdcodec.v21.RdiExtensionChannels21
import io.netty.util.AttributeKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.apache.logging.log4j.LogManager

/** Selective batching of vanilla attribute updates; off unless `-Drdi.batch.enabled=true`. */
object RServerBatching {
    private val logger = LogManager.getLogger("rdi.network-extensions")
    private val settingsKey = AttributeKey.valueOf<BatchSettings21>("rdi.batch.settings21")

    fun onPlayerJoined(player: ServerPlayer) {
        val connection = player.connection.connection
        val name = player.gameProfile.name
        val channel = connection.channel()
        val attribute = channel.attr(settingsKey)
        val settings = attribute.get() ?: BatchSettings21.read(System::getProperty)
            .let { attribute.setIfAbsent(it) ?: it }
        channel.eventLoop().execute {
            if (!channel.isActive) return@execute
            try {
                if (ZstdCompressionPipeline.isOutboundBatchingEnabled(channel)) return@execute
                val decision = settings.decide(
                    connection.isMemoryConnection,
                    RdiExtensionChannels21.isNegotiated(connection, RdiExtensionChannels21.BATCH),
                    ZstdCompressionPipeline.hasCompressionEncoder(channel),
                    ZstdCompressionPipeline.hasOutboundEncoder(channel),
                )
                if (decision != ExtensionDecision21.Enable) {
                    if (decision != ExtensionDecision21.Disabled) {
                        logger.info("Player {} keeps unbatched packets: {}", name, decision)
                    }
                    return@execute
                }
                // Nothing is armed yet, so a missing adapter only means this connection stays unbatched.
                if (!PacketRecordPipeline.enableSelective(channel)) {
                    logger.warn("Player {} keeps unbatched packets: packet record adapter is not installed", name)
                    return@execute
                }
                ZstdCompressionPipeline.setOutboundBatching(channel, true, delayUnassociated = false)
                check(ZstdCompressionPipeline.isOutboundBatchingEnabled(channel)) { "Packet batching activation failed" }
                logger.info("Player {} attribute batching active, handlers={}", name,
                    ZstdCompressionPipeline.describeHandlers(channel))
            } catch (error: Exception) {
                logger.error("Unable to activate packet batching for ${name}", error)
                ZstdInboundPreparation.abort(channel, error)
            }
        }
    }

    /** One flush task per batching connection, queued after every packet sent during this tick. */
    fun flushAtTickEnd(server: MinecraftServer) {
        for (player in server.playerList.players) {
            val channel = player.connection.connection.channel()
            if (!ZstdCompressionPipeline.isOutboundBatchingEnabled(channel)) continue
            channel.eventLoop().execute {
                if (channel.isActive) ZstdCompressionPipeline.flushAtTickEnd(channel)
            }
        }
    }
}
