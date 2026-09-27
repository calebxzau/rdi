package calebxzau.rdi.mc.v20.server.mixin;

import calebxzhou.rdi.mc.common.RDI;
import net.minecraft.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Util.class)
public class mLimitExecutor {
    @Redirect(
            method = "makeExecutor",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(III)I")
    )
    private static int rdi$limitExecutor(int value, int min, int max) {
        return RDI.DEBUG ? 32 : 2;
    }
}
