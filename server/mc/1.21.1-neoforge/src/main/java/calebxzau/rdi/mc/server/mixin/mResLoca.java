package calebxzau.rdi.mc.server.mixin;

import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * calebxzhou @ 2026-08-05 16:14
 */
@Mixin(ResourceLocation.class)
public class mResLoca {
    @Inject(method = "assertValidPath",at=@At("HEAD"), cancellable = true)
    private static void RDI$assertValidPath(String namespace, String path, CallbackInfoReturnable<String> cir) { cir.setReturnValue(path); }
}
