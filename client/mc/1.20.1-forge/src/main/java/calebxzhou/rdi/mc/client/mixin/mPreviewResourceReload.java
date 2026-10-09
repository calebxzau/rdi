package calebxzhou.rdi.mc.client.mixin;

import calebxzau.rdi.mc.client.preview.ItemPreviewExporter;
import net.minecraft.client.ResourceLoadStateTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Both initial loading and successful manual/recovery reloads finish here in 1.20.1. */
@Mixin(ResourceLoadStateTracker.class)
public abstract class mPreviewResourceReload {
    @Inject(method = "finishReload", at = @At("TAIL"))
    private void rdi$previewResourcesReady(CallbackInfo ci) {
        ItemPreviewExporter.INSTANCE.onResourceLoadFinished();
    }
}
