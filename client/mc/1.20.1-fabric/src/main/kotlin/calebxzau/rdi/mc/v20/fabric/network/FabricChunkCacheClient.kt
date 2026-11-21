package calebxzau.rdi.mc.v20.fabric.network

import calebxzau.rdi.mc.chunkcache.network.ChunkCachePayload
import calebxzau.rdi.mc.client.chunkcache.ChunkCacheCompat
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import org.slf4j.LoggerFactory

object FabricChunkCacheClient : ChunkCacheCompat.Transport {
    private val logger = LoggerFactory.getLogger(FabricChunkCacheClient::class.java)

    fun register() {
        ChunkCacheCompat.transport = this
        for (message in FabricChunkCacheWire.clientbound) {
            ClientPlayNetworking.registerGlobalReceiver(message.id) { client, listener, buffer, _ ->
                val payload = runCatching { message.decode(buffer) }.getOrElse { error ->
                    logger.warn("Invalid chunk cache payload {}", message.id, error)
                    return@registerGlobalReceiver
                }
                // Enqueue in arrival order alongside vanilla packets and reject stale connections.
                client.execute {
                    if (client.connection === listener) ChunkCacheCompat.receive(listener, payload)
                }
            }
        }
    }

    override fun sendToServer(payload: ChunkCachePayload) {
        val id = FabricChunkCacheWire.channel(payload).id
        if (ClientPlayNetworking.canSend(id)) ClientPlayNetworking.send(id, FabricChunkCacheWire.encode(payload))
    }

    override fun canReportPause(listener: ClientPacketListener): Boolean =
        Minecraft.getInstance().connection === listener && ClientPlayNetworking.canSend(FabricChunkCacheWire.Paused.id)
}
