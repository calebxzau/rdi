package calebxzhou.rdi.mc.server.network

import calebxzau.mc.common2021.RdiZstdStreamChannel
import calebxzau.rdi.mc.v20.server.network.ServerPacketExtensions20
import net.minecraft.server.level.ServerPlayer

object RServerZstdStream {
    fun onPlayerJoined(player: ServerPlayer) {
        val connection = player.connection.connection
        if (connection.isMemoryConnection) return
        ServerPacketExtensions20.startStream(
            connection.channel(),
            player.gameProfile.name,
            RdiZstdStreamChannel.isRemotePresent(connection),
        )
    }
}
