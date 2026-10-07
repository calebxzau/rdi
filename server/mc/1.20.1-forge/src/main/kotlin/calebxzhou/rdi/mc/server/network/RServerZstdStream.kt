package calebxzhou.rdi.mc.server.network

import calebxzau.mc.common2021.RdiZstdStreamChannel
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.ZstdStreamFormat
import net.minecraft.server.level.ServerPlayer
import org.apache.logging.log4j.LogManager

/**
 * Forge-only activation of the Zstd stream for server-to-client packets.
 *
 * On unless the server runs with `-Drdi.zstream.enabled=false`; `-Drdi.zstream.windowLog` sets the
 * history each connection keeps outside the Java heap on both ends, 2^windowLog bytes.
 */
object RServerZstdStream {
    private val logger = LogManager.getLogger("rdi")

    internal data class Settings(val enabled: Boolean, val windowLog: Int)

    internal enum class Decision { Disabled, MemoryConnection, RemoteAbsent, CompressionOff, ForeignEncoder, Enable }

    internal fun readSettings(property: (String) -> String?): Settings = Settings(
        enabled = property("rdi.zstream.enabled")?.toBoolean() ?: true,
        windowLog = windowLogSetting(property("rdi.zstream.windowLog")),
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
            remotePresent = RdiZstdStreamChannel.isRemotePresent(connection),
            hasEncoder = ZstdCompressionPipeline.hasCompressionEncoder(channel),
            hasRdiEncoder = ZstdCompressionPipeline.hasOutboundEncoder(channel),
        )
        when (decision) {
            Decision.Disabled, Decision.MemoryConnection -> Unit
            Decision.RemoteAbsent -> logger.info("Player {} keeps independent Zstd frames; rdi:zstream is absent", name)
            Decision.CompressionOff -> logger.info("Player {} keeps independent Zstd frames; network compression is off", name)
            Decision.ForeignEncoder -> logger.error(
                "Player {} keeps independent Zstd frames; another mod replaced the compression encoder: {}",
                name,
                ZstdCompressionPipeline.describeHandlers(channel),
            )
            Decision.Enable -> {
                ZstdCompressionPipeline.setOutboundStream(channel, settings.windowLog)
                logger.info(
                    "Player {} Zstd stream requested, windowLog={} ({} MiB per side), handlers={}",
                    name,
                    settings.windowLog,
                    1 shl (settings.windowLog - 20),
                    ZstdCompressionPipeline.describeHandlers(channel),
                )
            }
        }
    }

    private fun windowLogSetting(value: String?): Int {
        val default = ZstdStreamFormat.DEFAULT_WINDOW_LOG
        val range = ZstdStreamFormat.MINIMUM_WINDOW_LOG..ZstdStreamFormat.MAXIMUM_WINDOW_LOG
        if (value == null) return default
        val parsed = value.trim().toIntOrNull() ?: return default.also {
            logger.warn("Ignoring rdi.zstream.windowLog={}; it is not an integer, using {}", value, default)
        }
        return parsed.coerceIn(range).also { accepted ->
            if (accepted != parsed) {
                logger.warn(
                    "Clamping rdi.zstream.windowLog={} to {}; the accepted range is {}-{}",
                    parsed,
                    accepted,
                    range.first,
                    range.last,
                )
            }
        }
    }
}
