package calebxzau.rdi.mc.v20.server.mixin;

import calebxzau.rdi.mc.v20.server.network.PacketMetricsPipeline20;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketEncoder.class)
public abstract class mPacketMetricsEncoder20 {
    @Unique private static final Logger RDI$LOGGER = LogManager.getLogger("rdi.packet-metrics");

    @Inject(
            method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At("RETURN")
    )
    private void rdi$recordPacket(ChannelHandlerContext context, Packet<?> packet, ByteBuf output, CallbackInfo ci) {
        try {
            PacketMetricsPipeline20.encoded(context, packet);
        } catch (Throwable error) {
            RDI$LOGGER.error("Failed to collect an outbound packet metric", error);
        }
    }
}
