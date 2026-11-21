package calebxzau.rdi.mc.server.chunkcache

import calebxzau.rdi.mc.chunkcache.network.ChunkCacheChannel
import calebxzau.rdi.mc.chunkcache.network.ChunkCachePayload
import net.minecraft.server.level.ServerPlayer

/** Registers the optional chunk cache channels; client-bound messages are ignored here. */
object ChunkCacheServerNetwork : ChunkCacheServerService.Transport {
    @JvmStatic
    fun register() {
        ChunkCacheServerService.transport = this
        ChunkCacheChannel.register { payload, context ->
            context.sender?.let { ChunkCacheServerService.receive(it, payload) }
        }
    }

    override fun isRemotePresent(player: ServerPlayer) = ChunkCacheChannel.isRemotePresent(player.connection.connection)

    override fun isWarmupRemotePresent(player: ServerPlayer) = ChunkCacheChannel.isWarmupRemotePresent(player.connection.connection)

    override fun sendToPlayer(player: ServerPlayer, payload: ChunkCachePayload) = ChunkCacheChannel.sendToPlayer(player, payload)

    override fun toClientPacket(payload: ChunkCachePayload) = ChunkCacheChannel.toClientPacket(payload)
}
