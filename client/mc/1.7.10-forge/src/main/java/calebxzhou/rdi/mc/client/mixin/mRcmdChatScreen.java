package calebxzhou.rdi.mc.client.mixin;

import calebxzhou.rdi.mc.client.rcmd.RcmdClientBridge1710;
import calebxzhou.rdi.mc.rcmd.RcmdClientCommands;
import calebxzhou.rdi.mc.rcmd.RcmdDispatchResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiChat.class)
public class mRcmdChatScreen {
    @Inject(method = "func_146403_a", at = @At("HEAD"), cancellable = true)
    private void rdi$handleClientRcmd(String message, CallbackInfo ci) {
        RcmdClientBridge1710 bridge = new RcmdClientBridge1710(Minecraft.getMinecraft());
        RcmdDispatchResult result = RcmdClientCommands.dispatch(bridge, message);
        if (!result.found()) {
            return;
        }

        Minecraft.getMinecraft().ingameGUI.getChatGUI().addToSentMessages(message);
        RcmdClientCommands.reply(bridge, result.result());
        ci.cancel();
    }
}
