package calebxzhou.rdi.mc.server.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.hm.efn.util.ParticleEffectInvoker", remap = false)
public abstract class mNightfallGroundSplit {
    @Inject(
            method = "groundSplit(Lyesman/epicfight/world/capabilities/entitypatch/LivingEntityPatch;DDDDFZZZZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hm/efn/util/EffekUnits;VFXENABLE()Z",
                    shift = At.Shift.BEFORE,
                    remap = false
            ),
            cancellable = true,
            remap = false,
            require = 1,
            allow = 1
    )
    private static void rdi$skipServerGroundVfx(CallbackInfo ci, @Local Level level) {
        // circleSlamFracture and its packet sync have already run here.
        // Stop before client-only effect types are initialized, including on an integrated server.
        if (!level.isClientSide()) {
            ci.cancel();
        }
    }
}
