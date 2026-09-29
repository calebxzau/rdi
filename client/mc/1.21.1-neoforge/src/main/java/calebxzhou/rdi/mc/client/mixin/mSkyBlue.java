package calebxzhou.rdi.mc.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.Difficulty;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * calebxzhou @ 2026-05-26 16:21
 * 地平线以下永远蓝色
 */
@Mixin(ClientLevel.ClientLevelData.class)
public class mSkyBlue {
    @Shadow
    @Final
    @Mutable
    private boolean isFlat;

    @Inject(method = "<init>",at=@At("TAIL"))
    private void RDI$SkyAlwaysBlue(Difficulty difficulty, boolean bl, boolean bl2, CallbackInfo ci){
        isFlat=true;
    }
}