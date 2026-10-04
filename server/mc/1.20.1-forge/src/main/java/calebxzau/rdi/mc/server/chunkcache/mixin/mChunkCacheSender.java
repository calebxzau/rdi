package calebxzau.rdi.mc.server.chunkcache.mixin;

import calebxzau.rdi.mc.server.chunkcache.ChunkCacheServerService;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 1.20.1 sends every newly tracked chunk through this call; the shared packet itself is never modified. */
@Mixin(ChunkMap.class)
public abstract class mChunkCacheSender {
    @WrapOperation(
            method = "playerLoadedChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;trackChunk(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/network/protocol/Packet;)V"
            )
    )
    private void rdi$reuseCachedTerrain(
            ServerPlayer player,
            ChunkPos pos,
            Packet<?> packet,
            Operation<Void> original,
            @Local(argsOnly = true) LevelChunk chunk
    ) {
        original.call(player, pos, ChunkCacheServerService.replace(player, packet, chunk));
    }
}
