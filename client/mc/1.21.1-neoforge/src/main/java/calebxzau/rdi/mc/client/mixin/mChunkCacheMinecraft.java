package calebxzau.rdi.mc.client.mixin;

import calebxzau.rdi.mc.client.chunkcache.RdiChunkCache;
import calebxzau.rdi.mc.client.chunkcache.ChunkCacheClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class mChunkCacheMinecraft {
    @Inject(method = "tick", at = @At("TAIL"))
    private void rdi$logChunkCacheStats(CallbackInfo ci) {
        ChunkCacheClient.tick();
        RdiChunkCache.tickStats();
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("HEAD"))
    private void rdi$endRoom(CallbackInfo ci) {
        RdiChunkCache.endConnection();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void rdi$finishWrites(CallbackInfo ci) {
        RdiChunkCache.shutdown();
    }
}
