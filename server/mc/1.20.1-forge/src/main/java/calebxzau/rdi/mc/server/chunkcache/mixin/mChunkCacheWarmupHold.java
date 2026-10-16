package calebxzau.rdi.mc.server.chunkcache.mixin;

import calebxzau.rdi.mc.server.chunkcache.ChunkCacheServerService;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a chunk cache session warms up, holds a newly tracked chunk back until the client has offered or declined it.
 * The whole of {@code playerLoadedChunk} waits, so the chunk, its entity links and {@code ChunkWatchEvent.Watch} still
 * arrive in vanilla order when warm-up sends it; a held chunk that leaves the view is neither unloaded nor unwatched.
 */
@Mixin(ChunkMap.class)
public abstract class mChunkCacheWarmupHold {
    @Shadow @Final ServerLevel level;

    @Inject(method = "playerLoadedChunk", at = @At("HEAD"), cancellable = true)
    private void rdi$holdForWarmup(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packetCache,
                                   LevelChunk chunk, CallbackInfo ci) {
        if (ChunkCacheServerService.hold(player, chunk)) ci.cancel();
    }

    @Inject(method = "updateChunkTracking", at = @At("HEAD"), cancellable = true)
    private void rdi$forgetHeld(ServerPlayer player, ChunkPos chunkPos,
                                MutableObject<ClientboundLevelChunkWithLightPacket> packetCache,
                                boolean wasLoaded, boolean load, CallbackInfo ci) {
        if (!load && wasLoaded && player.level() == level && ChunkCacheServerService.forgetHeld(player, level, chunkPos)) ci.cancel();
    }
}
