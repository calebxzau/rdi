package calebxzau.rdi.mc.v20.client.mixin;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SkinManager.class)
public interface ASkinManager {
    @Invoker("registerTexture")
    ResourceLocation rdi$registerTexture(
            MinecraftProfileTexture profileTexture,
            MinecraftProfileTexture.Type textureType,
            @Nullable SkinManager.SkinTextureCallback skinAvailableCallback
    );
}
