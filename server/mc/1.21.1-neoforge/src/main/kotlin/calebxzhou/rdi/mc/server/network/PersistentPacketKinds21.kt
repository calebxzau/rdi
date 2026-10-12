package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.zstdcodec.PersistentPacketKind
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.CommonPacketTypes
import net.minecraft.network.protocol.game.GamePacketTypes

/**
 * The Minecraft 1.21.1 packets a client may keep across joins of the same host: recipes at join and tags
 * during configuration (and after a reload). Matches wire types, so no packet class is initialised.
 */
internal object PersistentPacketKinds21 {
    fun kindOf(packet: Packet<*>): Int = when (packet.type()) {
        GamePacketTypes.CLIENTBOUND_UPDATE_RECIPES -> PersistentPacketKind.RECIPES
        CommonPacketTypes.CLIENTBOUND_UPDATE_TAGS -> PersistentPacketKind.TAGS
        else -> PersistentPacketKind.NONE
    }
}
