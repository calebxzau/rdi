package calebxzhou.rdi.mc.server.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Epic Fight: Nightfall gates its Effekseer VFX on {@code EffekUnits.VFXENABLE()}, which reads a client
 * config (default true) and checks whether aaa_particles is installed. On a dedicated server with
 * aaa_particles present it returns true, and callers such as {@code ParticleEffectInvoker.groundSplit}
 * then load client-only {@code com.hm.efn.client.effek.*} classes, which kicks the player.
 * <p>
 * Do not target {@code ParticleEffectInvoker} itself: transforming it makes ModLauncher recompute frames
 * that reference {@code ClientLevel}, which cannot be loaded on a dedicated server.
 * <p>
 * Applied only on a dedicated server with efn loaded (see {@link RMixinPlugin}).
 */
@Pseudo
@Mixin(targets = "com.hm.efn.util.EffekUnits", remap = false)
public abstract class mNightfallVfxServer {
    @Inject(method = "VFXENABLE()Z", at = @At("HEAD"), cancellable = true, remap = false)
    private static void rdi$disableVfxOnDedicatedServer(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
