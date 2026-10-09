package calebxzau.rdi.mc.server.mixin;

import calebxzhou.rdi.mc.server.network.PacketMetricsPipeline;
import calebxzhou.rdi.mc.server.network.PacketRecordPipeline;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.local.LocalChannel;
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
public abstract class mPacketMetricsEncoder {
    @Unique private static final Logger RDI$LOGGER = LogManager.getLogger("rdi.packet-metrics");
    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V", at = @At("RETURN"))
    private void rdi$recordPacket(ChannelHandlerContext context, Packet<?> packet, ByteBuf output, CallbackInfo ci) {
        try {
            if (context.channel() instanceof LocalChannel || context.channel().remoteAddress() == null) return;

            // Batching classification first: it must not depend on metrics collection succeeding.
            PacketRecordPipeline.encoded(context, packet, output);
            PacketMetricsPipeline.encoded(context, packet);
        } catch (Throwable error) {
            RDI$LOGGER.error("Failed to collect an outbound packet metric", error);
        }
    }

}
