package calebxzhou.rdi.mc.client.mixin;

import calebxzhou.rdi.mc.client.texture.EffekAvifTextureConverter;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.logging.LogUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.io.IOException;

@Pseudo
@Mixin(targets = "mod.chloeprime.aaaparticles.api.client.effekseer.EffekseerEffect", remap = false)
public abstract class EffekAvifTextureMixin {
    @WrapMethod(
            method = "loadTexture([BIILmod/chloeprime/aaaparticles/api/client/effekseer/TextureType;)Z",
            remap = false,
            require = 1,
            allow = 1
    )
    private boolean rdi$convertAvifTexture(byte[] data, int length, int index, @Coerce Object type,
                                          Operation<Boolean> original) throws IOException {
        byte[] textureBytes;
        try {
            textureBytes = EffekAvifTextureConverter.toPngIfAvif(data, length);
        } catch (IOException | RuntimeException error) {
            LogUtils.getLogger().error("Failed to prepare Effekseer texture at index {} (type {})", index, type, error);
            throw error;
        }
        return original.call(textureBytes, textureBytes == data ? length : textureBytes.length, index, type);
    }
}
