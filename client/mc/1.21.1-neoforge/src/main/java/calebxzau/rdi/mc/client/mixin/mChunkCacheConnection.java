package calebxzau.rdi.mc.client.mixin;

import calebxzau.rdi.mc.client.chunkcache.RdiChunkCache;
import calebxzau.rdi.mc.client.dm.DmParticipantSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(ConnectScreen.class)
public abstract class mChunkCacheConnection {
    @Inject(method = "connect", at = @At("HEAD"))
    private void rdi$beginConnection(Minecraft minecraft, ServerAddress address, ServerData data,
                               TransferState transfer, CallbackInfo ci) {
        DmParticipantSession.onConnecting(data != null ? data.ip : null);
        UUID dmHostId = DmParticipantSession.INSTANCE.expectedHostId(data != null ? data.ip : null);
        RdiChunkCache.beginConnection(dmHostId != null ? dmHostId.toString() : null);
    }
}
