package calebxzhou.rdi.mc.client.mixin;

import calebxzau.rdi.mc.zstdcodec.v1710.ZstdLoginSwitch1710;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.login.server.S02PacketLoginSuccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Installs the switch on the event loop after NetworkManager has acquired its channel. */
@Mixin(NetworkManager.class)
public abstract class mZstdLoginSwitch1710 {
    @Shadow @Final private boolean isClientSide;

    @Inject(method = "channelActive(Lio/netty/channel/ChannelHandlerContext;)V", at = @At("TAIL"), remap = false)
    private void rdi$installZstd(ChannelHandlerContext context, CallbackInfo ci) {
        if (isClientSide) {
            ZstdLoginSwitch1710.installClient(context.channel(), msg -> msg instanceof S02PacketLoginSuccess);
        }
    }
}
