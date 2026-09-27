package calebxzau.rdi.mc.v20.server.mixin;

import calebxzau.rdi.mc.v20.server.rcmd.RcmdServerRuntime20;
import calebxzhou.rdi.mc.rcmd.Rcmd;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class mRcmdPlayerChat20 {
    @Shadow
    public ServerPlayer player;

    @Shadow
    private void detectRateSpam() {
    }

    @Inject(method = "broadcastChatMessage", at = @At("HEAD"), cancellable = true)
    private void rdi$routeRcmdAndChat(PlayerChatMessage message, CallbackInfo ci) {
        String content = message.signedContent();
        if (!Rcmd.isRcmd(content)) {
            String filteredContent = message.filterMask().apply(content);
            if (filteredContent == null) {
                this.detectRateSpam();
                ci.cancel();
                return;
            }
            content = message.filterMask().isEmpty()
                    ? message.decoratedContent().getString()
                    : filteredContent;
        }
        if (RcmdServerRuntime20.handlePlayerChat(player, content)) {
            this.detectRateSpam();
            ci.cancel();
        }
    }
}
