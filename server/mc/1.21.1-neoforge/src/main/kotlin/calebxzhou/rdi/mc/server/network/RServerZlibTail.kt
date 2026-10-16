package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.ZstdInboundPreparation
import calebxzau.rdi.mc.zstdcodec.v21.ExtensionDecision21
import calebxzau.rdi.mc.zstdcodec.v21.RdiExtensionChannels21
import calebxzau.rdi.mc.zstdcodec.v21.ZlibTailSettings21
import io.netty.channel.Channel
import io.netty.util.AttributeKey
import net.minecraft.server.level.ServerPlayer
import org.apache.logging.log4j.LogManager

/**
 * Default-on zlib tails for negotiated remote clients. Disable with -Drdi.zlibtail.enabled=false; pick the
 * mods with -Drdi.zlibtail.namespaces (comma separated, "*" for every mod).
 */
object RServerZlibTail {
    private val logger = LogManager.getLogger("rdi.network-extensions")
    private val settingsKey = AttributeKey.valueOf<ZlibTailSettings21>("rdi.zlibtail.settings21")

    internal fun settings(channel: Channel, property: (String) -> String?): ZlibTailSettings21 {
        val attribute = channel.attr(settingsKey)
        return attribute.get() ?: ZlibTailSettings21.read(property) { logger.warn(it) }
            .let { attribute.setIfAbsent(it) ?: it }
    }

    /** Called only on the event loop; the client decoder accepts zlib tails since Configuration ended. */
    internal fun activate(channel: Channel, settings: ZlibTailSettings21, memory: Boolean, negotiated: Boolean): ExtensionDecision21 {
        check(channel.eventLoop().inEventLoop()) { "Zlib tails belong to the connection event loop" }
        val decision = settings.decide(memory, negotiated,
            ZstdCompressionPipeline.hasCompressionEncoder(channel),
            ZstdCompressionPipeline.hasOutboundEncoder(channel))
        if (decision != ExtensionDecision21.Enable) return decision
        ZstdCompressionPipeline.setOutboundZlibTail(channel, settings.namespaces)
        check(ZstdCompressionPipeline.isOutboundZlibTailEnabled(channel)) { "Zlib tail activation failed" }
        return decision
    }

    fun onPlayerJoined(player: ServerPlayer) {
        val connection = player.connection.connection
        val channel = connection.channel()
        val name = player.gameProfile.name
        val settings = settings(channel, System::getProperty)
        channel.eventLoop().execute {
            if (!channel.isActive) return@execute
            try {
                val decision = activate(channel, settings, connection.isMemoryConnection,
                    RdiExtensionChannels21.isNegotiated(connection, RdiExtensionChannels21.ZLIB_TAIL))
                if (decision == ExtensionDecision21.Enable) {
                    logger.info("Player {} zlib tails active, namespaces={}", name, settings.namespaces)
                } else if (decision != ExtensionDecision21.Disabled) {
                    logger.info("Player {} keeps mod-deflated payloads: {}", name, decision)
                }
            } catch (error: Exception) {
                logger.error("Unable to activate zlib tails for ${name}", error)
                ZstdInboundPreparation.abort(channel, error)
            }
        }
    }
}
