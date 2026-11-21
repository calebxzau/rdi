package calebxzhou.rdi.mc.server.network

import calebxzau.mc.common2021.RdiPacketRefChannel
import calebxzau.rdi.mc.v20.server.network.ServerPacketExtensions20
import net.minecraft.server.level.ServerPlayer

object RServerPacketRefs {
    fun onPlayerJoined(player: ServerPlayer) {
        val connection = player.connection.connection
        if (connection.isMemoryConnection) return
        ServerPacketExtensions20.startReferences(
            connection.channel(),
            player.gameProfile.name,
            RdiPacketRefChannel.isRemotePresent(connection),
        )
    }
}
