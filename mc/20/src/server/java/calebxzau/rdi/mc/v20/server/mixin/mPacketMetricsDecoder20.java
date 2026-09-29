package calebxzau.rdi.mc.v20.server.mixin;

import calebxzau.rdi.mc.v20.server.network.PacketMetricsPipeline20;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.protocol.Packet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(PacketDecoder.class)
public abstract class mPacketMetricsDecoder20 {
    @Unique private static final Logger RDI$LOGGER = LogManager.getLogger("rdi.packet-metrics");
    @Unique private int rdi$outputSizeBefore;
    @Unique private boolean rdi$hadInput;

    @Inject(method = "decode", at = @At("HEAD"))
    private void rdi$capturePacketStart(ChannelHandlerContext context, ByteBuf input, List<Object> output, CallbackInfo ci) {
        rdi$hadInput = input.isReadable();
        rdi$outputSizeBefore = output.size();
    }

    @Inject(method = "decode", at = @At("RETURN"))
    private void rdi$recordPacket(ChannelHandlerContext context, ByteBuf input, List<Object> output, CallbackInfo ci) {
        try {
            if (!rdi$hadInput) {
                PacketMetricsPipeline20.discarded(context);
                return;
            }
            if (output.size() <= rdi$outputSizeBefore) {
                PacketMetricsPipeline20.discarded(context);
                return;
            }
            Object decoded = output.get(output.size() - 1);
            if (!(decoded instanceof Packet<?> packet)) {
                PacketMetricsPipeline20.discarded(context);
                return;
            }
            PacketMetricsPipeline20.decoded(context, packet);
        } catch (Throwable error) {
            RDI$LOGGER.error("Failed to collect an inbound packet metric", error);
        }
    }
}
