package calebxzau.rdi.mc.v20.fabric.network

import calebxzau.rdi.mc.chunkcache.network.ChunkCachePayload
import calebxzau.rdi.mc.server.chunkcache.ChunkCacheServerService
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.protocol.Packet
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory

object FabricChunkCacheServer : ChunkCacheServerService.Transport {
    private val logger = LoggerFactory.getLogger(FabricChunkCacheServer::class.java)

    fun register() {
        ChunkCacheServerService.transport = this
        for (message in FabricChunkCacheWire.serverbound) {
            ServerPlayNetworking.registerGlobalReceiver(message.id) { server, player, _, buffer, _ ->
                val payload = runCatching { message.decode(buffer) }.getOrElse { error ->
                    logger.warn("Invalid chunk cache payload {} from {}", message.id, player.scoreboardName, error)
                    return@registerGlobalReceiver
                }
                server.execute {
                    if (server.playerList.getPlayer(player.uuid) === player) ChunkCacheServerService.receive(player, payload)
                }
            }
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> ChunkCacheServerService.initialize(handler.player) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            val player = handler.player
            server.execute { ChunkCacheServerService.reset(player) }
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            server.playerList.players.forEach(ChunkCacheServerService::tick)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { ChunkCacheServerService.clear() }
    }

    override fun isRemotePresent(player: ServerPlayer): Boolean =
        ServerPlayNetworking.canSend(player, FabricChunkCacheWire.Context.id) &&
            ServerPlayNetworking.canSend(player, FabricChunkCacheWire.Reuse.id) &&
            ServerPlayNetworking.canSend(player, FabricChunkCacheWire.Retire.id)

    override fun isWarmupRemotePresent(player: ServerPlayer): Boolean =
        ServerPlayNetworking.canSend(player, FabricChunkCacheWire.Warmup.id)

    override fun sendToPlayer(player: ServerPlayer, payload: ChunkCachePayload) {
        ServerPlayNetworking.send(player, FabricChunkCacheWire.channel(payload).id, FabricChunkCacheWire.encode(payload))
    }

    override fun toClientPacket(payload: ChunkCachePayload): Packet<*> =
        ServerPlayNetworking.createS2CPacket(FabricChunkCacheWire.channel(payload).id, FabricChunkCacheWire.encode(payload))
}
