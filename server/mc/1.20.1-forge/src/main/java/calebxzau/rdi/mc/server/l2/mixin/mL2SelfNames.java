package calebxzau.rdi.mc.server.l2.mixin;

import calebxzau.rdi.mc.server.l2.RServerL2Names;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.xkmc.l2tabs.init.L2Tabs", remap = false)
abstract class mL2SelfNames {
    @Inject(method = "lambda$onAttributeUpdate$2", at = @At("HEAD"), cancellable = true, remap = false)
    private static void rdi$coalesceSelfNames(@Coerce Object packet, ServerPlayer player, CallbackInfo ci) {
        if (RServerL2Names.suppressLegacy(player)) ci.cancel();
    }
}
