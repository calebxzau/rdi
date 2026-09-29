package calebxzau.rdi.mc.server.mixin;

import calebxzhou.rdi.mc.server.network.PacketMetricsPipeline;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.BandwidthDebugMonitor;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
abstract class mPacketMetricsConnection {
    @Inject(method = "configureSerialization", at = @At("RETURN"))
    private static void rdi$installMetrics(ChannelPipeline pipeline, PacketFlow flow, boolean memoryOnly,
                                          BandwidthDebugMonitor monitor, CallbackInfo ci) {
        if (!memoryOnly && flow == PacketFlow.SERVERBOUND) PacketMetricsPipeline.install(pipeline);
    }
}
