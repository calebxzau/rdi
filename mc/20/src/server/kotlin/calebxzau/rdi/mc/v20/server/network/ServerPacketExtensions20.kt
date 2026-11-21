package calebxzau.rdi.mc.v20.server.network

import calebxzau.rdi.mc.zstdcodec.PacketRefFormat
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.ZstdInboundPreparation
import calebxzau.rdi.mc.zstdcodec.ZstdStreamFormat
import io.netty.channel.Channel
import org.apache.logging.log4j.LogManager

object ServerPacketExtensions20 {
    private val logger = LogManager.getLogger("rdi")

    data class BatchSettings(val enabled: Boolean, val longWindow: Boolean, val entityBatching: Boolean)
    data class StreamSettings(val enabled: Boolean, val windowLog: Int)
    data class ReferenceSettings(val enabled: Boolean, val slots: Int, val maxEntryBytes: Int)

    enum class Decision { Disabled, MemoryConnection, RemoteAbsent, CompressionOff, ForeignEncoder, Enable }

    fun batchSettings(property: (String) -> String? = System::getProperty) = BatchSettings(
        enabled = property("rdi.batch.enabled")?.toBoolean() ?: true,
        longWindow = property("rdi.batch.longWindow")?.toBoolean() ?: false,
        entityBatching = property("rdi.batch.entity")?.toBoolean() ?: true,
    )

    fun streamSettings(property: (String) -> String? = System::getProperty) = StreamSettings(
        enabled = property("rdi.zstream.enabled")?.toBoolean() ?: true,
        windowLog = intSetting(
            property, "rdi.zstream.windowLog", ZstdStreamFormat.DEFAULT_WINDOW_LOG,
            ZstdStreamFormat.MINIMUM_WINDOW_LOG..ZstdStreamFormat.MAXIMUM_WINDOW_LOG,
        ),
    )

    fun referenceSettings(property: (String) -> String? = System::getProperty) = ReferenceSettings(
        enabled = property("rdi.pktref.enabled")?.toBoolean() ?: true,
        slots = intSetting(
            property, "rdi.pktref.slots", PacketRefFormat.DEFAULT_SLOTS,
            PacketRefFormat.MINIMUM_SLOTS..PacketRefFormat.MAXIMUM_SLOTS,
        ),
        maxEntryBytes = intSetting(
            property, "rdi.pktref.maxEntryBytes", PacketRefFormat.DEFAULT_MAX_ENTRY_BYTES,
            PacketRefFormat.MINIMUM_ENTRY_BYTES..PacketRefFormat.MAXIMUM_ENTRY_BYTES,
        ),
    )

    fun offeredExtensions(): Int {
        var extensions = 0
        if (batchSettings().enabled) extensions = extensions or ZstdInboundPreparation.BATCH
        if (streamSettings().enabled) extensions = extensions or ZstdInboundPreparation.STREAM
        if (referenceSettings().enabled) extensions = extensions or ZstdInboundPreparation.REFERENCES
        return extensions
    }

    fun decide(
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

    fun startBatching(channel: Channel, name: String, verifiedChannels: Set<String>) {
        val settings = batchSettings()
        configureBatching(channel, settings, verifiedChannels)
        logger.info("Player {} selective Zstd batching requested={}, long window={}, entity updates={}, handlers={}",
            name, settings.enabled, settings.longWindow, settings.entityBatching,
            ZstdCompressionPipeline.describeHandlers(channel))
    }

    fun flushBatching(channel: Channel, settings: BatchSettings, verifiedChannels: Set<String>) {
        // Policy changes and flushes must stay ordered with the connection's writes.
        channel.eventLoop().execute {
            if (!channel.isActive) return@execute
            configureBatching(channel, settings, verifiedChannels)
            if (settings.enabled) ZstdCompressionPipeline.flushAtTickEnd(channel)
        }
    }

    private fun configureBatching(channel: Channel, settings: BatchSettings, verifiedChannels: Set<String>) {
        PacketMetricsPipeline20.configureBatching(channel, settings.longWindow, verifiedChannels, settings.entityBatching)
        if (ZstdCompressionPipeline.isOutboundBatchingEnabled(channel) != settings.enabled) {
            ZstdCompressionPipeline.setOutboundBatching(channel, settings.enabled)
        }
    }

    fun startStream(channel: Channel, name: String, remotePresent: Boolean) {
        val settings = streamSettings()
        if (!shouldStart(channel, name, "zstream", settings.enabled, remotePresent)) return
        ZstdCompressionPipeline.setOutboundStream(channel, settings.windowLog)
        logger.info("Player {} Zstd stream requested, windowLog={} ({} MiB per side), handlers={}",
            name, settings.windowLog, 1 shl (settings.windowLog - 20),
            ZstdCompressionPipeline.describeHandlers(channel))
    }

    fun startReferences(channel: Channel, name: String, remotePresent: Boolean) {
        val settings = referenceSettings()
        if (!shouldStart(channel, name, "pktref", settings.enabled, remotePresent)) return
        ZstdCompressionPipeline.setOutboundPacketRefs(channel, settings.slots, settings.maxEntryBytes)
        logger.info("Player {} packet references requested, slots={}, maxEntryBytes={}, handlers={}",
            name, settings.slots, settings.maxEntryBytes, ZstdCompressionPipeline.describeHandlers(channel))
    }

    private fun shouldStart(channel: Channel, name: String, extension: String, enabled: Boolean, remotePresent: Boolean): Boolean {
        val decision = decide(enabled, false, remotePresent,
            ZstdCompressionPipeline.hasCompressionEncoder(channel), ZstdCompressionPipeline.hasOutboundEncoder(channel))
        when (decision) {
            Decision.Disabled, Decision.MemoryConnection -> Unit
            Decision.RemoteAbsent -> logger.info("Player {} keeps legacy packets; rdi:{} is absent", name, extension)
            Decision.CompressionOff -> logger.info("Player {} skips rdi:{}; network compression is off", name, extension)
            Decision.ForeignEncoder -> logger.error("Player {} skips rdi:{}; unexpected compression encoder: {}",
                name, extension, ZstdCompressionPipeline.describeHandlers(channel))
            Decision.Enable -> return true
        }
        return false
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
