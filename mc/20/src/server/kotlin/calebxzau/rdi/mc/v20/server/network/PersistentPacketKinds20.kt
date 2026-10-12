package calebxzau.rdi.mc.v20.server.network

import calebxzau.rdi.mc.zstdcodec.PersistentPacketKind
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket
import net.minecraft.network.protocol.game.ClientboundUpdateTagsPacket

/** The Minecraft 1.20.1 packets a client may keep across joins of the same host. */
object PersistentPacketKinds20 {
    fun kindOf(packet: Packet<*>?): Int = when (packet) {
        is ClientboundUpdateRecipesPacket -> PersistentPacketKind.RECIPES
        is ClientboundUpdateTagsPacket -> PersistentPacketKind.TAGS
        else -> PersistentPacketKind.NONE
    }
}
