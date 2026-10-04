package calebxzhou.rdi.mc.server.network

import calebxzau.mc.common2021.RdiPacketRefChannel
import calebxzau.rdi.mc.zstdcodec.PacketRefFormat
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import net.minecraft.server.level.ServerPlayer
import org.apache.logging.log4j.LogManager

/**
 * Forge-only activation of packet references for server-to-client packets.
 *
 * Off unless the server runs with `-Drdi.pktref.enabled=true`; turning it off again takes a restart
 * and no client change.
 */
object RServerPacketRefs {
    private val logger = LogManager.getLogger("rdi")

    internal data class Settings(val enabled: Boolean, val slots: Int, val maxEntryBytes: Int)

    internal enum class Decision { Disabled, MemoryConnection, RemoteAbsent, CompressionOff, ForeignEncoder, Enable }

    internal fun readSettings(property: (String) -> String?): Settings = Settings(
        enabled = property("rdi.pktref.enabled")?.toBoolean() ?: true,
        slots = intSetting(
            property,
            "rdi.pktref.slots",
            PacketRefFormat.DEFAULT_SLOTS,
            PacketRefFormat.MINIMUM_SLOTS..PacketRefFormat.MAXIMUM_SLOTS,
        ),
        maxEntryBytes = intSetting(
            property,
            "rdi.pktref.maxEntryBytes",
            PacketRefFormat.DEFAULT_MAX_ENTRY_BYTES,
            PacketRefFormat.MINIMUM_ENTRY_BYTES..PacketRefFormat.MAXIMUM_ENTRY_BYTES,
        ),
    )

    internal fun decide(
        enabled: Boolean,
        memoryConnection: Boolean,
        remotePresent: Boolean,
        hasEncoder: Boolean,
        hasRdiEncoder: Boolean,
    ): Decision = when {
        !enabled -> Decision.Disabled
        memoryConnection -> Decision.MemoryConnection
        !remotePresent -> Decision.RemoteAbsent
        !hasEncoder -> Decision.CompressionOff
        !hasRdiEncoder -> Decision.ForeignEncoder
        else -> Decision.Enable
    }

    fun onPlayerJoined(player: ServerPlayer) {
        val settings = readSettings(System::getProperty)
        val connection = player.connection.connection
        if (!settings.enabled || connection.isMemoryConnection) return
        val channel = connection.channel()
        val name = player.gameProfile.name
        val decision = decide(
            enabled = true,
            memoryConnection = false,
            remotePresent = RdiPacketRefChannel.isRemotePresent(connection),
            hasEncoder = ZstdCompressionPipeline.hasCompressionEncoder(channel),
            hasRdiEncoder = ZstdCompressionPipeline.hasOutboundEncoder(channel),
        )
        when (decision) {
            Decision.Disabled, Decision.MemoryConnection -> Unit
            Decision.RemoteAbsent -> logger.info("Player {} keeps plain packets; rdi:pktref is absent", name)
            Decision.CompressionOff -> logger.info("Player {} keeps plain packets; network compression is off", name)
            Decision.ForeignEncoder -> logger.error(
                "Player {} keeps plain packets; another mod replaced the compression encoder: {}",
                name,
                ZstdCompressionPipeline.describeHandlers(channel),
            )
            Decision.Enable -> {
                ZstdCompressionPipeline.setOutboundPacketRefs(channel, settings.slots, settings.maxEntryBytes)
                logger.info(
                    "Player {} packet references requested, slots={}, maxEntryBytes={}, handlers={}",
                    name,
                    settings.slots,
                    settings.maxEntryBytes,
                    ZstdCompressionPipeline.describeHandlers(channel),
                )
            }
        }
    }

    private fun intSetting(property: (String) -> String?, key: String, default: Int, range: IntRange): Int {
        val value = property(key) ?: return default
        val parsed = value.trim().toIntOrNull() ?: return default.also {
            logger.warn("Ignoring {}={}; it is not an integer, using {}", key, value, default)
        }
        return parsed.coerceIn(range).also { accepted ->
            if (accepted != parsed) {
                logger.warn("Clamping {}={} to {}; the accepted range is {}-{}", key, parsed, accepted, range.first, range.last)
            }
        }
    }
}
