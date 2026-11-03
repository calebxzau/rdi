package calebxzhou.rdi.mc.client.mixin;

import calebxzau.rdi.mc.client.loading.RootLogLevelFix;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public class mWindow {
    // Runs in Minecraft.<init> after FML's preLaunch logging reload and before ClientModLoader.begin.
    @Inject(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J"
            )
    )
    private void rdi$lowerRootLogLevel(CallbackInfo ci) {
        RootLogLevelFix.applyFromSystemProperty();
    }
}
