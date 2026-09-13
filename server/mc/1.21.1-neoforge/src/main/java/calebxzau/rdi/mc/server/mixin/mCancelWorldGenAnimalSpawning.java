package calebxzau.rdi.mc.server.mixin;

import calebxzhou.rdi.mc.common.RDI;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NaturalSpawner.class)
public class mCancelWorldGenAnimalSpawning {
    @Inject(method = "spawnMobsForChunkGeneration", at = @At("HEAD"), cancellable = true)
    private static void rdi$cancelWorldGenAnimalSpawning(CallbackInfo ci) {
        if (RDI.ONLY_SAVE_FIRM_SECTIONS) {
            ci.cancel();
        }
    }
}
