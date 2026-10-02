package calebxzau.rdi.mc.server.chunkcache

import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

@EventBusSubscriber(modid = "rdi")
object ChunkCacheServerEvents {
    @JvmStatic
    @SubscribeEvent
    fun onServerTick(event: ServerTickEvent.Post) {
        event.server.playerList.players.forEach(ChunkCacheServerService::tick)
    }

    @JvmStatic
    @SubscribeEvent
    fun onPlayerLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        (event.entity as? net.minecraft.server.level.ServerPlayer)?.let(ChunkCacheServerService::reset)
    }

    @JvmStatic
    @SubscribeEvent
    fun onPlayerLogin(event: PlayerLoggedInEvent) {
        (event.entity as? net.minecraft.server.level.ServerPlayer)?.let(ChunkCacheServerService::initialize)
    }

    @JvmStatic
    @SubscribeEvent
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        (event.entity as? net.minecraft.server.level.ServerPlayer)?.let {
            ChunkCacheServerService.reset(it)
            ChunkCacheServerService.initialize(it)
        }
    }

    @JvmStatic
    @SubscribeEvent
    fun onPlayerChangedDimension(event: PlayerChangedDimensionEvent) {
        (event.entity as? net.minecraft.server.level.ServerPlayer)?.let {
            ChunkCacheServerService.reset(it)
            ChunkCacheServerService.initialize(it)
        }
    }

    @JvmStatic
    @SubscribeEvent
    fun onServerStopping(@Suppress("UNUSED_PARAMETER") event: ServerStoppingEvent) {
        ChunkCacheServerService.clear()
    }
}
