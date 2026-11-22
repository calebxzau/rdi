package calebxzhou.rdi.mc.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReceiver;
import com.llamalad7.mixinextras.sugar.Cancellable;
import net.minecraftforge.registries.RegistryObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.github.darksonic300.mob_effect_vfx.ClientSideRenderingEvents", remap = false)
public abstract class mMobEffectsVfxParticles {
    @ModifyReceiver(
            method = "spawnParticles(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/world/effect/MobEffect;Lnet/minecraft/world/entity/LivingEntity;Lcom/github/darksonic300/mob_effect_vfx/util/MEVColor;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraftforge/registries/RegistryObject;get()Ljava/lang/Object;"),
            require = 0,
            remap = false
    )
    private static RegistryObject<?> rdi$skipMissingParticle(RegistryObject<?> particle, @Cancellable CallbackInfo ci) {
        if (!particle.isPresent()) {
            ci.cancel();
        }
        return particle;
    }
}
