package calebxzhou.rdi.mc.client.mixin;

import calebxzau.rdi.mc.client.preview.PreviewAtlasRenderer;
import com.mojang.blaze3d.vertex.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VertexBuffer.class)
public abstract class mPreviewVertexBuffer {
    @Inject(
            method = "_drawWithShader(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/ShaderInstance;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;draw()V"
            )
    )
    private void rdi$rebindPreviewTargetBeforeDraw(CallbackInfo ci) {
        PreviewAtlasRenderer.rdiPreviewRebindItemTargetBeforeDraw();
    }
}
