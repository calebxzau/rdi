package calebxzau.rdi.mc.v20.server.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerChunkCache.class)
abstract class mGuardChunkTick {
    @WrapOperation(
            method = "runDistanceManagerUpdates()Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/DistanceManager;runAllUpdates(Lnet/minecraft/server/level/ChunkMap;)Z")
    )
    private boolean rdi$guardDistanceManagerUpdates(
            DistanceManager distanceManager,
            ChunkMap chunkMap,
            Operation<Boolean> original
    ) {
        try {
            return original.call(distanceManager, chunkMap);
        } catch (Exception error) {
            error.printStackTrace();
            return true;
        }
    }
}
