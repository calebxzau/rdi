package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.ZstdInboundPreparation
import calebxzau.rdi.mc.zstdcodec.v21.ExtensionDecision21
import calebxzau.rdi.mc.zstdcodec.v21.PacketRefSettings21
import calebxzau.rdi.mc.zstdcodec.v21.RdiExtensionChannels21
import io.netty.channel.Channel
import io.netty.util.AttributeKey
import net.minecraft.server.level.ServerPlayer
import org.apache.logging.log4j.LogManager

/** Default-on references for negotiated remote clients. Disable with -Drdi.pktref.enabled=false. */
object RServerPacketRefs {
    private val logger = LogManager.getLogger("rdi.network-extensions")
    private val settingsKey = AttributeKey.valueOf<PacketRefSettings21>("rdi.pktref.settings21")
    private val startedKey = AttributeKey.valueOf<Boolean>("rdi.pktref.started21")

    internal fun settings(channel: Channel, property: (String) -> String?): PacketRefSettings21 {
        val attribute = channel.attr(settingsKey)
        return attribute.get() ?: PacketRefSettings21.read(property) { logger.warn(it) }
            .let { attribute.setIfAbsent(it) ?: it }
    }

    /** Called only on the event loop, including the START write and eligibility checks. */
    internal fun activate(channel: Channel, settings: PacketRefSettings21, memory: Boolean, negotiated: Boolean): ExtensionDecision21 {
        check(channel.eventLoop().inEventLoop()) { "Packet references belong to the connection event loop" }
        val decision = settings.decide(memory, negotiated,
            ZstdCompressionPipeline.hasCompressionEncoder(channel),
            ZstdCompressionPipeline.hasOutboundEncoder(channel))
        if (decision != ExtensionDecision21.Enable) return decision
        // Mark before START: a downstream write/flush listener can reenter activation.
        if (channel.attr(startedKey).get() == true) return decision
        channel.attr(startedKey).set(true)
        ZstdCompressionPipeline.setOutboundPacketRefs(channel, settings.slots, settings.maxEntryBytes)
        check(ZstdCompressionPipeline.isOutboundPacketRefsEnabled(channel)) { "Packet reference activation failed" }
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
                    RdiExtensionChannels21.isNegotiated(connection, RdiExtensionChannels21.REFERENCES))
                if (decision == ExtensionDecision21.Enable) {
                    logger.info("Player {} packet references active, slots={}, maxEntryBytes={}",
                        name, settings.slots, settings.maxEntryBytes)
                } else if (decision != ExtensionDecision21.Disabled) {
                    logger.info("Player {} keeps plain packets: {}", name, decision)
                }
            } catch (error: Exception) {
                logger.error("Unable to activate packet references for ${name}", error)
                ZstdInboundPreparation.abort(channel, error)
            }
        }
    }
}
