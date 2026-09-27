package calebxzhou.rdi.mc.client.mixin;

import calebxzhou.rdi.mc.common.RDI;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * calebxzhou @ 2025-10-21 10:33
 */
@Mixin(ServerboundHelloPacket.class)
class mServerboundHelloPacket {
    @ModifyConstant(method = "write",constant = @Constant(intValue = 16))
    private static int RDI$WiderLengthForChineseName(int constant){
        return 64;
    }
}
@Mixin(ClientIntentionPacket.class)
class mClientIntentionPacket {
    @Shadow
    @Final
    @Mutable
    private int port;

    @Inject(method = "<init>(Ljava/lang/String;ILnet/minecraft/network/ConnectionProtocol;)V",at=@At("TAIL"))
    private void RDI$InjectHostPort(String hostName, int port, ConnectionProtocol intention, CallbackInfo ci){
        this.port = calebxzhou.rdi.mc.common.RDI.HOST_PORT;
    }
    @Redirect(method = "write",at= @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;writeShort(I)Lio/netty/buffer/ByteBuf;"))
    private ByteBuf RDI$WriteCorrectPort(FriendlyByteBuf buf, int value){
        buf.writeShort(RDI.HOST_PORT);
        return buf;
    }
}