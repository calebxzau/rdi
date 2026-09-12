package calebxzhou.rdi.mc.client.mixin;

import net.minecraftforge.client.event.ScreenEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
        targets = "com.farmingtales.serverlinks.client.ClientScreenEvents",
        remap = false
)
public abstract class mFarmingTalesServerLinks {
    @Inject(
            method = "onMultiplayerScreenInit(Lnet/minecraftforge/client/event/ScreenEvent$Init$Post;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void rdi$disableFarmingTalesServerLinks(ScreenEvent.Init.Post event, CallbackInfo ci) {
        ci.cancel();
    }
}
