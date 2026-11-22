package calebxzhou.rdi.mc.client.mixin;

import calebxzhou.rdi.mc.client.compat.ClientEntityFailures;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EntityRenderDispatcher.class)
public abstract class mClientEntityRenderGuard {
    @WrapMethod(method = "shouldRender")
    private boolean rdi$shouldRender(Entity entity, Frustum frustum, double x, double y, double z, Operation<Boolean> original) {
        if (ClientEntityFailures.isHidden(entity)) return false;
        try {
            return original.call(entity, frustum, x, y, z);
        } catch (Throwable error) {
            ClientEntityFailures.record(entity, "visibility check", error);
            return false;
        }
    }

    @WrapMethod(method = "getPackedLightCoords")
    private int rdi$getPackedLight(Entity entity, float partialTick, Operation<Integer> original) {
        if (ClientEntityFailures.isHidden(entity)) return 0;
        try {
            return original.call(entity, partialTick);
        } catch (Throwable error) {
            ClientEntityFailures.record(entity, "render lighting", error);
            return 0;
        }
    }

    @WrapMethod(method = "render")
    private void rdi$renderEntity(Entity entity, double x, double y, double z, float yaw, float partialTick,
                                  PoseStack poses, MultiBufferSource buffers, int light, Operation<Void> original) {
        if (ClientEntityFailures.isHidden(entity)) return;
        PoseStack.Pose entry = poses.last();
        try {
            original.call(entity, x, y, z, yaw, partialTick, poses, buffers, light);
        } catch (Throwable error) {
            ClientEntityFailures.record(entity, "render", error);
            // A throwing renderer can leave several nested transforms above the caller's pose.
            if (((APoseStack) poses).rdi$poses().contains(entry)) {
                while (poses.last() != entry) poses.popPose();
            }
        }
    }
}
