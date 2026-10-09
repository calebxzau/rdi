package calebxzhou.rdi.mc.client.mixin;

import calebxzhou.rdi.mc.client.network.ClientNetworkExtensions21;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConfigurationPacketListenerImpl.class)
public abstract class mConfigurationFinished extends ClientCommonPacketListenerImpl {
    private mConfigurationFinished(Minecraft minecraft, Connection connection, CommonListenerCookie cookie) {
        super(minecraft, connection, cookie);
    }

    @Inject(method = "handleConfigurationFinished", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;)V"),
        cancellable = true, require = 1, allow = 1)
    private void rdi$prepareExtensions(ClientboundFinishConfigurationPacket packet, CallbackInfo ci) {
        try {
            ClientNetworkExtensions21.prepare(this.connection);
        } catch (Exception error) {
            LogManager.getLogger("rdi.network-extensions").error("Cannot prepare network extensions before Play", error);
            this.connection.disconnect(Component.literal("网络连接准备失败，请重新进入房间"));
            ci.cancel();
        }
    }
}
