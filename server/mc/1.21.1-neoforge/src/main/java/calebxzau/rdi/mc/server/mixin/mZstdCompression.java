package calebxzau.rdi.mc.server.mixin;

import calebxzau.rdi.mc.server.network.MinecraftVarIntCodec211;
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class mZstdCompression {
    @Shadow
    public abstract Channel channel();

    @Inject(method = "setupCompression", at = @At("HEAD"), cancellable = true)
    private void RDI$setupCompression(int threshold, boolean validateDecompressed, CallbackInfo ci) {
        ZstdCompressionPipeline.setup(channel(), threshold, validateDecompressed, MinecraftVarIntCodec211.INSTANCE);
        ci.cancel();
    }
}
