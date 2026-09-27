package calebxzau.rdi.mc.v20.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

@Mixin(Options.class)
public abstract class mOptions {
    @Shadow
    public String languageCode;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void rdi$forceChineseLanguage(Minecraft minecraft, File gameDirectory, CallbackInfo ci) {
        this.languageCode = "zh_cn";
    }
}
