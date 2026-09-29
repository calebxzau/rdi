package calebxzau.rdi.mc.v20.server.mixin;

import calebxzau.rdi.mc.v20.server.network.PacketMetricsPipeline20;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
abstract class mPacketMetricsConnection20 {
    @Inject(method = "configureSerialization", at = @At("RETURN"))
    private static void rdi$installMetrics(ChannelPipeline pipeline, PacketFlow flow, CallbackInfo ci) {
        if (flow != PacketFlow.SERVERBOUND) return;
        PacketMetricsPipeline20.install(pipeline);
    }
}
