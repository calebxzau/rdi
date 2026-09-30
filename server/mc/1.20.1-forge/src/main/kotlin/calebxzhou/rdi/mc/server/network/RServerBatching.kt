package calebxzhou.rdi.mc.server.network

import calebxzau.mc.common2021.RdiBatchChannel
import calebxzau.rdi.mc.v20.server.network.PacketBatchPolicy20
import calebxzau.rdi.mc.v20.server.network.PacketMetricsPipeline20
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.fml.ModList
import org.apache.logging.log4j.LogManager

/** Forge-only activation; shared codec and other loaders stay inactive by default. */
object RServerBatching {
    private val logger = LogManager.getLogger("rdi")
    private val verifiedChannels: Set<String> by lazy {
        val version = ModList.get().getModContainerById("l2tabs").orElse(null)
            ?.modInfo?.version?.toString()
        if (version == PacketBatchPolicy20.L2_TABS_VERSION) {
            setOf(PacketBatchPolicy20.L2_TABS_CHANNEL)
        } else {
            logger.info("L2 Tabs version {} has no verified delayed-message policy; its messages send immediately", version)
            emptySet()
        }
    }

    private fun batchingEnabled(): Boolean = System.getProperty("rdi.batch.enabled", "true").toBoolean()
    private fun longWindowEnabled(): Boolean = System.getProperty("rdi.batch.longWindow", "false").toBoolean()

    fun onPlayerJoined(player: ServerPlayer) {
        val connection = player.connection.connection
        if (!RdiBatchChannel.isRemotePresent(connection)) {
            logger.info("Player {} keeps legacy Zstd envelopes; rdi:batch is absent", player.gameProfile.name)
            return
        }
        val channel = connection.channel()
        PacketMetricsPipeline20.configureBatching(channel, longWindowEnabled(), verifiedChannels)
        ZstdCompressionPipeline.setOutboundBatching(channel, batchingEnabled())
        logger.info("Player {} selective Zstd batching enabled={}, long window={}",
            player.gameProfile.name, batchingEnabled(), longWindowEnabled())
    }

    fun flushAtTickEnd(server: MinecraftServer) {
        val enabled = batchingEnabled()
        val longWindow = longWindowEnabled()
        for (player in server.playerList.players) {
            val connection = player.connection.connection
            if (!RdiBatchChannel.isRemotePresent(connection)) continue
            val channel = connection.channel()
            // Policy updates and deadlines share one ordered Netty task.
            channel.eventLoop().execute {
                if (!channel.isActive) return@execute
                PacketMetricsPipeline20.configureBatching(channel, longWindow, verifiedChannels)
                if (ZstdCompressionPipeline.isOutboundBatchingEnabled(channel) != enabled) {
                    ZstdCompressionPipeline.setOutboundBatching(channel, enabled)
                }
                if (enabled) ZstdCompressionPipeline.flushAtTickEnd(channel)
            }
        }
    }
}
