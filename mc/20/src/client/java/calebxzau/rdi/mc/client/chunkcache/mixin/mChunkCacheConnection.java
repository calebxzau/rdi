package calebxzau.rdi.mc.client.chunkcache.mixin;

import calebxzau.rdi.mc.client.chunkcache.RdiChunkCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public abstract class mChunkCacheConnection {
    @Inject(method = "connect", at = @At("HEAD"))
    private void rdi$beginConnection(Minecraft minecraft, ServerAddress address, ServerData data, CallbackInfo ci) {
        RdiChunkCache.beginConnection();
    }
}
