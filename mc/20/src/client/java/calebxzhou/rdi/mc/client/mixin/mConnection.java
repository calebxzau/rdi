package calebxzhou.rdi.mc.client.mixin;

import com.mojang.logging.LogUtils;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
class mConnection {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Shadow
    @Final
    private PacketFlow receiving;

    @Inject(method = "exceptionCaught", at = @At("HEAD"))
    private void RDI$LogClientConnectionException(
        ChannelHandlerContext context,
        Throwable exception,
        CallbackInfo ci
    ) {
        if (receiving == PacketFlow.CLIENTBOUND) {
            LOGGER.error("客户端Netty连接发生异常", exception);
        }
    }
}
