package calebxzau.rdi.mc.client.mixin;

import calebxzau.mc.common2021.mcp.McpChatCapture;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Chat output for the MCP command tool. */
@Mixin(ChatComponent.class)
public abstract class mMcpChatCapture {
    @Inject(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
        at = @At("HEAD")
    )
    private void rdi$captureMcpChat(Component message, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
        if (McpChatCapture.isRecording()) McpChatCapture.onChat(message.getString());
    }
}
