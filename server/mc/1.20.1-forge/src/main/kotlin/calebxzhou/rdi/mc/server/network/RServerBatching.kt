package calebxzhou.rdi.mc.server.network

import calebxzau.mc.common2021.RdiBatchChannel
import calebxzau.rdi.mc.v20.server.network.PacketBatchPolicy20
import calebxzau.rdi.mc.v20.server.network.PacketMetricsPipeline20
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.ZstdStreamSampler
import io.netty.channel.Channel
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
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
        startSampler(player, channel)
        logger.info("Player {} selective Zstd batching enabled={}, long window={}",
            player.gameProfile.name, batchingEnabled(), longWindowEnabled())
    }

    private fun startSampler(player: ServerPlayer, channel: Channel) {
        val selected = System.getProperty("rdi.batch.samplePlayer") ?: return
        if (selected != player.gameProfile.name) return
        val seconds = System.getProperty("rdi.batch.sampleSeconds", "30").toLongOrNull()?.coerceIn(1, 300) ?: 30
        val mebibytes = System.getProperty("rdi.batch.sampleMiB", "16").toLongOrNull()?.coerceIn(1, 64) ?: 16
        val sampler = ZstdStreamSampler(seconds * 1000, mebibytes * 1024 * 1024)
        val output = player.server.serverDirectory.toPath().resolve("rdi").resolve(
            "batch-stream-${Instant.now().toEpochMilli()}-${player.uuid}.rdibatch",
        )
        channel.eventLoop().execute {
            if (!channel.isActive || !ZstdCompressionPipeline.hasOutboundEncoder(channel)) return@execute
            PacketMetricsPipeline20.attachSampler(channel, sampler)
            var finished = false
            val finish = {
                if (!finished) {
                    finished = true
                    PacketMetricsPipeline20.attachSampler(channel, null)
                    sampler.finish()
                    val capture = sampler.snapshot()
                    CompletableFuture.runAsync {
                        capture.export(output).fold(
                            onSuccess = { logger.info("Packet diagnostic stream exported to {}", it) },
                            onFailure = { logger.error("Failed to export packet diagnostic stream", it) },
                        )
                    }
                }
            }
            val timeout = channel.eventLoop().schedule({ finish() }, seconds, TimeUnit.SECONDS)
            channel.closeFuture().addListener {
                timeout.cancel(false)
                finish()
            }
        }
    }

    fun flushAtTickEnd(server: MinecraftServer) {
        val enabled = batchingEnabled()
        val longWindow = longWindowEnabled()
        for (player in server.playerList.players) {
            val connection = player.connection.connection
            if (!RdiBatchChannel.isRemotePresent(connection)) continue
            val channel = connection.channel()
            // Policy updates, capture tick markers and deadlines share one ordered Netty task.
            channel.eventLoop().execute {
                if (!channel.isActive) return@execute
                PacketMetricsPipeline20.configureBatching(channel, longWindow, verifiedChannels)
                PacketMetricsPipeline20.sampleTick(channel)
                if (ZstdCompressionPipeline.isOutboundBatchingEnabled(channel) != enabled) {
                    ZstdCompressionPipeline.setOutboundBatching(channel, enabled)
                }
                if (enabled) ZstdCompressionPipeline.flushAtTickEnd(channel)
            }
        }
    }
}
