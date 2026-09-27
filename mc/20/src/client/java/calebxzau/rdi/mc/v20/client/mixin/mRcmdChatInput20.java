package calebxzau.rdi.mc.v20.client.mixin;

import calebxzau.rdi.mc.v20.client.RcmdClientBridge20;
import calebxzhou.rdi.mc.rcmd.RcmdClientCommands;
import calebxzhou.rdi.mc.rcmd.RcmdDispatchResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChatScreen.class)
public class mRcmdChatInput20 {
    @Redirect(
            method = "handleChatInput",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;sendChat(Ljava/lang/String;)V"
            )
    )
    private void rdi$sendRcmdOrChat(ClientPacketListener connection, String message) {
        if (!RcmdClientCommands.isRcmd(message)) {
            connection.sendChat(message);
            return;
        }
        var bridge = new RcmdClientBridge20(Minecraft.getInstance());
        RcmdDispatchResult result = RcmdClientCommands.dispatch(bridge, message);
        if (!result.found()) {
            connection.sendChat(message);
            return;
        }
        RcmdClientCommands.reply(bridge, result.result());
    }
}
