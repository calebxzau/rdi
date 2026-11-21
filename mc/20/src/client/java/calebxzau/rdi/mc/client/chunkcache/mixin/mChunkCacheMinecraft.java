package calebxzau.rdi.mc.client.chunkcache.mixin;

import calebxzau.rdi.mc.client.chunkcache.ChunkCacheClient;
import calebxzau.rdi.mc.client.chunkcache.RdiChunkCache;
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

    // 1.20.1 leaves a server through clearLevel(Screen); ConnectScreen also calls it before connecting.
    @Inject(method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
    private void rdi$endRoom(CallbackInfo ci) {
        RdiChunkCache.endConnection();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void rdi$finishWrites(CallbackInfo ci) {
        RdiChunkCache.shutdown();
    }
}
