package calebxzau.rdi.mc.v20.fabric.mixin;

import calebxzau.rdi.mc.syncchunk.client.SyncChunkOutlines;
import calebxzau.rdi.mc.v20.fabric.syncchunk.SyncChunkIntegratedFabric;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class mImportLevelRenderer {
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void rdi$renderImportMarks(PoseStack poses, float partialTick, long finishTime,
                                       boolean renderBlockOutline, Camera camera, GameRenderer renderer,
                                       LightTexture light, Matrix4f projection, CallbackInfo callback) {
        if (SyncChunkIntegratedFabric.getMarking()) {
            // Vanilla has already restored the model-view stack here. Camera rotation is in poses.
            SyncChunkOutlines.INSTANCE.render(poses, camera.getPosition());
        }
    }
}
