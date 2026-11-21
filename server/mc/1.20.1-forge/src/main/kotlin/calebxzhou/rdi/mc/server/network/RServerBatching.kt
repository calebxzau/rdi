package calebxzhou.rdi.mc.server.network

import calebxzau.mc.common2021.RdiBatchChannel
import calebxzau.rdi.mc.v20.server.network.PacketBatchPolicy20
import calebxzau.rdi.mc.v20.server.network.ServerPacketExtensions20
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.fml.ModList
import org.apache.logging.log4j.LogManager

/** Forge channel and verified mod-payload adapter for shared 1.20.1 batching. */
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

    fun onPlayerJoined(player: ServerPlayer) {
        val connection = player.connection.connection
        if (!RdiBatchChannel.isRemotePresent(connection)) {
            logger.info("Player {} keeps legacy Zstd envelopes; rdi:batch is absent", player.gameProfile.name)
            return
        }
        ServerPacketExtensions20.startBatching(connection.channel(), player.gameProfile.name, verifiedChannels)
    }

    fun flushAtTickEnd(server: MinecraftServer) {
        val settings = ServerPacketExtensions20.batchSettings()
        for (player in server.playerList.players) {
            val connection = player.connection.connection
            if (!RdiBatchChannel.isRemotePresent(connection)) continue
            ServerPacketExtensions20.flushBatching(connection.channel(), settings, verifiedChannels)
        }
    }
}
