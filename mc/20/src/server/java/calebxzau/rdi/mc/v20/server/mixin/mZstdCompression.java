package calebxzau.rdi.mc.v20.server.mixin;

import calebxzau.rdi.mc.v20.server.network.MinecraftVarIntCodec20;
import calebxzau.rdi.mc.v20.server.network.PacketMetricsPipeline20;
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
abstract class mZstdCompression {
    @Shadow
    private Channel channel;

    @Inject(method = "setupCompression", at = @At("HEAD"), cancellable = true)
    private void RDI$SetupCompression(int threshold, boolean validateDecompressed, CallbackInfo ci) {
        Runnable setup = () -> {
            ZstdCompressionPipeline.setup(channel, threshold, validateDecompressed, MinecraftVarIntCodec20.INSTANCE);
            PacketMetricsPipeline20.thresholdChanged(channel, threshold);
            PacketMetricsPipeline20.compressionChanged(channel.pipeline());
        };
        if (channel.eventLoop().inEventLoop()) setup.run();
        else channel.eventLoop().execute(setup);
        ci.cancel();
    }
}
