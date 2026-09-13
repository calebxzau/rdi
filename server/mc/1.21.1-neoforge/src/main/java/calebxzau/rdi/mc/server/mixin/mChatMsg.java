package calebxzau.rdi.mc.server.mixin;

import calebxzhou.rdi.mc.common.WebSocketClient;
import calebxzhou.rdi.mc.common.WsMessage;
import calebxzhou.rdi.mc.rcmd.Rcmd;
import calebxzhou.rdi.mc.rcmd.RcmdResult;
import calebxzhou.rdi.mc.rcmd.RcmdSource;
import calebxzhou.rdi.mc.rcmd.chat.PlayerChatRangeState;
import calebxzhou.rdi.mc.rcmd.chat.RChatMessage;
import calebxzhou.rdi.mc.server.rcmd.RcmdServerCommands;
import calebxzhou.rdi.mc.server.rcmd.RcmdServerSource211;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

import static calebxzhou.rdi.mc.common.RDI.HOST_ID;

/**
 * calebxzhou @ 2025-12-30 22:40
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class mChatMsg {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleChat",at=@At("HEAD"), cancellable = true)
    private void RDI$chat(ServerboundChatPacket packet, CallbackInfo ci){
        String message = packet.message();
        if (Rcmd.isRcmd(message)) {
            ci.cancel();
            RcmdSource source = new RcmdServerSource211(player);
            RcmdResult result = RcmdServerCommands.dispatcher().execute(source, message);
            RcmdServerCommands.reply(source, result);
            return;
        }
        if (PlayerChatRangeState.isGlobal(player.getUUID())) {
            RChatMessage chatMessage = new RChatMessage(
                    UUID.randomUUID().toString(),
                    HOST_ID,
                    player.getUUID().toString(),
                    player.getGameProfile().getName(),
                    message,
                    System.currentTimeMillis(),
                    true
            );
            if (WebSocketClient.sendMessage(WsMessage.Channel.Chat, chatMessage)) {
                Component component = Component.literal("[公共] " + player.getGameProfile().getName() + ": " + message);
                for (ServerPlayer recipient : player.server.getPlayerList().getPlayers()) {
                    if (PlayerChatRangeState.isGlobal(recipient.getUUID())) {
                        recipient.sendSystemMessage(component);
                    }
                }
            } else {
                player.sendSystemMessage(Component.literal("聊天服务未连接"));
            }
            ci.cancel();
            return;
        }
        RChatMessage chatMessage = new RChatMessage(
                UUID.randomUUID().toString(),
                HOST_ID,
                player.getUUID().toString(),
                player.getGameProfile().getName(),
                message,
                System.currentTimeMillis(),
                false
        );
        if (WebSocketClient.sendMessage(WsMessage.Channel.Chat, chatMessage)) {
            player.server.getPlayerList().broadcastSystemMessage(Component.literal(player.getDisplayName().getString()+": "+message),false);
        } else {
            player.sendSystemMessage(Component.literal("聊天服务未连接"));
        }
        ci.cancel();
    }
}
