package calebxzhou.rdi.mc.client.mixin;

import calebxzhou.rdi.mc.client.texture.AvifNativeImageAdapter;
import calebxzhou.rdi.mc.client.texture.RNativeImagePixels;
import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.ByteBuffer;

@Mixin(NativeImage.class)
public abstract class mNativeImage implements RNativeImagePixels {
    @Shadow
    private long pixels;

    @Shadow @Final
    private long size;

    @Override
    public void copyRdiRgba(byte[] rgba) {
        if (rgba.length != size) {
            throw new IllegalArgumentException("RGBA pixel size does not match NativeImage");
        }
        ByteBuffer destination = MemoryUtil.memByteBuffer(pixels, Math.toIntExact(size));
        destination.put(rgba);
    }

    @Inject(
            method = "read(Lcom/mojang/blaze3d/platform/NativeImage$Format;Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/platform/NativeImage;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void rdi$decodeAvif(
            NativeImage.Format format,
            ByteBuffer textureData,
            CallbackInfoReturnable<NativeImage> cir
    ) throws IOException {
        NativeImage image = AvifNativeImageAdapter.decodeIfAvif(format, textureData);
        if (image != null) {
            cir.setReturnValue(image);
        }
    }
}
