package calebxzau.rdi.mc.server.chunkcache

import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

@Mod.EventBusSubscriber(modid = "rdi")
object ChunkCacheServerEvents {
    @JvmStatic
    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        event.server.playerList.players.forEach(ChunkCacheServerService::tick)
    }

    @JvmStatic
    @SubscribeEvent
    fun onPlayerLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        (event.entity as? ServerPlayer)?.let(ChunkCacheServerService::reset)
    }

    @JvmStatic
    @SubscribeEvent
    fun onPlayerLogin(event: PlayerEvent.PlayerLoggedInEvent) {
        (event.entity as? ServerPlayer)?.let(ChunkCacheServerService::initialize)
    }

    @JvmStatic
    @SubscribeEvent
    fun onPlayerRespawn(event: PlayerEvent.PlayerRespawnEvent) {
        // Chunks are sent before this event; a new player instance or dimension already started a session.
        (event.entity as? ServerPlayer)?.let(ChunkCacheServerService::initialize)
    }

    @JvmStatic
    @SubscribeEvent
    fun onPlayerChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        // Chunks are sent before this event; a new player instance or dimension already started a session.
        (event.entity as? ServerPlayer)?.let(ChunkCacheServerService::initialize)
    }

    @JvmStatic
    @SubscribeEvent
    fun onServerStopping(@Suppress("UNUSED_PARAMETER") event: ServerStoppingEvent) {
        ChunkCacheServerService.clear()
    }
}
