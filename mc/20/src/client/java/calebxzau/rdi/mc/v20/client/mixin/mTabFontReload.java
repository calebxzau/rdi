package calebxzau.rdi.mc.v20.client.mixin;

import calebxzau.rdi.mc.v20.client.TabLayoutCache;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FontManager.class)
public abstract class mTabFontReload {
    @Inject(method = {"apply", "setRenames"}, at = @At("RETURN"))
    private void rdi$invalidateTabText(CallbackInfo ci) {
        TabLayoutCache.invalidateFont();
    }
}
