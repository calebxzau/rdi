package calebxzhou.rdi.mc.client.mixin;

import calebxzhou.rdi.mc.client.network.MinecraftVarIntCodec201;
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Own the negotiated wire codec before another mod can cancel setupCompression at HEAD.
@Mixin(value = Connection.class, priority = ZstdCompressionPipeline.SETUP_MIXIN_PRIORITY)
public abstract class mZstdCompression {
    @Shadow
    private Channel channel;

    @Inject(method = "setupCompression", at = @At("HEAD"), cancellable = true)
    private void RDI$setupCompression(int threshold, boolean validateDecompressed, CallbackInfo ci) {
        ZstdCompressionPipeline.setup(channel, threshold, validateDecompressed, MinecraftVarIntCodec201.INSTANCE);
        ci.cancel();
    }
}
