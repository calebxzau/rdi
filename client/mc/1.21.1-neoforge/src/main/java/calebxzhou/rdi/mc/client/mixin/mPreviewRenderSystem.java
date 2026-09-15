package calebxzhou.rdi.mc.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderSystem.class)
public interface mPreviewRenderSystem {
    @Accessor("shaderLightDirections")
    static Vector3f[] rdiPreviewShaderLightDirections() {
        throw new AssertionError();
    }

}
