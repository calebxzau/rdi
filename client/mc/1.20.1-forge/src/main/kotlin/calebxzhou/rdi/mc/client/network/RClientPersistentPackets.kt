package calebxzhou.rdi.mc.client.network

import calebxzau.rdi.mc.v20.client.network.ClientPersistentPackets20
import calebxzau.rdi.mc.zstdcodec.PersistentPacketQuery
import io.netty.buffer.Unpooled
import net.minecraft.network.Connection
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket

object RClientPersistentPackets {
    /** True when the Forge login listener should leave the answer to RDI. */
    @JvmStatic
    fun onQuery(connection: Connection, packet: ClientboundCustomQueryPacket): Boolean {
        val identifier = packet.identifier
        if (identifier.namespace != PersistentPacketQuery.CHANNEL_NAMESPACE || identifier.path != PersistentPacketQuery.CHANNEL_PATH) {
            return false
        }
        val transactionId = packet.transactionId
        ClientPersistentPackets20.prepareReply(connection.channel(), packet.data).thenAccept { body ->
            val data = body?.let { FriendlyByteBuf(Unpooled.wrappedBuffer(it)) }
            connection.send(ServerboundCustomQueryPacket(transactionId, data))
        }
        return true
    }
}
