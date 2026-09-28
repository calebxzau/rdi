package calebxzau.rdi.mc.v20.server.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
abstract class mAllowPlayerListing {
    // RDI uses status samples to identify players online in a room.
    @Inject(method = "allowsListing", at = @At("RETURN"), cancellable = true)
    private void rdi$allowPlayerListing(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }
}
