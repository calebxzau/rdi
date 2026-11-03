package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.zstdcodec.v21.ZstdStreamActivation21
import net.minecraft.server.level.ServerPlayer

object RServerZstdStream {
    fun onPlayerJoined(player: ServerPlayer) {
        ZstdStreamActivation21.activate(player.connection.connection, player.gameProfile.name)
    }
}
