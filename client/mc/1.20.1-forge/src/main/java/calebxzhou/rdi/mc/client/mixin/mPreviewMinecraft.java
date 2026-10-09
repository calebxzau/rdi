package calebxzhou.rdi.mc.client.mixin;

import calebxzau.rdi.mc.client.preview.ItemPreviewExporter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(Minecraft.class)
public abstract class mPreviewMinecraft {
    @Inject(method = "reloadResourcePacks(Z)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
    private void rdi$previewReloadStarted(boolean recovery, CallbackInfoReturnable<CompletableFuture<Void>> ci) {
        ItemPreviewExporter.INSTANCE.onResourceReloadStarted();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void rdi$previewShutdown(CallbackInfo ci) {
        ItemPreviewExporter.INSTANCE.shutdown();
    }
}
