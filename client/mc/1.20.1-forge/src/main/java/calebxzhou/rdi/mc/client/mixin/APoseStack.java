package calebxzhou.rdi.mc.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Deque;

@Mixin(PoseStack.class)
public interface APoseStack {
    @Accessor("poseStack")
    Deque<PoseStack.Pose> rdi$poses();
}
