package calebxzau.rdi.mc.server.mixin;

import net.minecraft.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * calebxzhou @ 2026-04-01 12:49
 */
@Mixin(Util.class)
public class mLimitExecutor {
    @Redirect(method = "makeExecutor",at= @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(III)I"))
    private static int rdi$limitexecute(int value, int min, int max){
        return 2;
    }
}
