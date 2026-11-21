package calebxzau.rdi.mc.client.chunkcache

import calebxzau.rdi.mc.chunkcache.network.ChunkCacheChannel
import calebxzau.rdi.mc.chunkcache.network.ChunkCachePayload
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener

/**
 * Registers the optional chunk cache channels; server-bound messages are only encoded here. Handlers run on the game
 * thread in order with vanilla packets, so the current connection is the listener a message belongs to.
 */
object ChunkCacheClientNetwork : ChunkCacheCompat.Transport {
    @JvmStatic
    fun register() {
        ChunkCacheCompat.transport = this
        ChunkCacheChannel.register { payload, _ ->
            Minecraft.getInstance().connection?.let { ChunkCacheCompat.receive(it, payload) }
        }
    }

    override fun sendToServer(payload: ChunkCachePayload) = ChunkCacheChannel.sendToServer(payload)

    override fun canReportPause(listener: ClientPacketListener) = ChunkCacheChannel.isStatusRemotePresent(listener.connection)
}
