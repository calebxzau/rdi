package calebxzau.rdi.mc.server.syncchunk

import calebxzau.rdi.mc.syncchunk.SyncChunkList
import calebxzau.rdi.mc.v20.forge.syncchunk.SyncChunkChannel
import calebxzau.rdi.mc.v20.server.syncchunk.SyncChunkSavedData20
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.slf4j.LoggerFactory

/** Sends the whole sync chunk list on join and after each change to players whose client supports it. */
@Mod.EventBusSubscriber(modid = "rdi")
object SyncChunkForgeNetwork {
    private val logger = LoggerFactory.getLogger(SyncChunkForgeNetwork::class.java)

    /** The list only flows to clients, so nothing is handled here. */
    @JvmStatic
    fun register() = SyncChunkChannel.register { _, _ -> }

    @SubscribeEvent
    @JvmStatic
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (!SyncChunkChannel.isRemotePresent(player.connection.connection)) return
        list(player.server)?.let { send(player, it) }
    }

    /** Runs on the server thread; a failed send to one player does not stop the others. */
    @JvmStatic
    fun broadcast(server: MinecraftServer) {
        val list = list(server) ?: return
        server.playerList.players.forEach { player ->
            if (SyncChunkChannel.isRemotePresent(player.connection.connection)) send(player, list)
        }
    }

    private fun list(server: MinecraftServer): SyncChunkList? =
        SyncChunkSavedData20.of(server).mapCatching { SyncChunkList.of(it.snapshot()) }.getOrElse { exception ->
            logger.error("Failed to prepare the sync chunk list", exception)
            null
        }

    private fun send(player: ServerPlayer, list: SyncChunkList) {
        try {
            SyncChunkChannel.send(player, list)
        } catch (exception: RuntimeException) {
            logger.error("Failed to send the sync chunk list to {}", player.gameProfile.name, exception)
        }
    }
}
