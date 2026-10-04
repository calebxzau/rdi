package calebxzhou.rdi.mc.client.mixin;

import calebxzhou.rdi.mc.client.network.RClientBatching;
import calebxzhou.rdi.mc.client.network.RClientPacketRefs;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
class mClientBatchingLogin {
    @Inject(method = "channelRead0", at = @At("HEAD"))
    private void RDI$enableBatchDecoderBeforeLogin(
        ChannelHandlerContext context,
        Packet<?> packet,
        CallbackInfo ci
    ) {
        if (packet instanceof ClientboundLoginPacket) {
            RClientBatching.INSTANCE.onLoginPacket((Connection)(Object)this);
            RClientPacketRefs.INSTANCE.onLoginPacket((Connection)(Object)this);
        }
    }
}
