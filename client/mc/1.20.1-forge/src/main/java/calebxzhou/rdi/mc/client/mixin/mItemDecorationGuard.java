package calebxzhou.rdi.mc.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GuiGraphics.class)
public abstract class mItemDecorationGuard {
    @Shadow
    @Final
    private PoseStack pose;

    @SuppressWarnings("removal")
    @WrapMethod(method = "renderItemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V")
    private void rdi$skipDecorationFailure(
            Font font,
            ItemStack stack,
            int x,
            int y,
            String text,
            Operation<Void> original,
            @Share("rdi$decorationPose") LocalRef<PoseStack.Pose> decorationPose
    ) {
        try {
            original.call(font, stack, x, y, text);
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Silently stop this decoration render and release only the pose frame it pushed.
            try {
                PoseStack.Pose ownedPose = decorationPose.get();
                if (ownedPose != null && this.pose.last() == ownedPose) {
                    this.pose.popPose();
                }
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable ignoredCleanupFailure) {
                // Best-effort cleanup must not turn a decoration error into a game crash.
            }
        }
    }

    @WrapOperation(
            method = "renderItemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0),
            require = 1
    )
    private void rdi$trackDecorationPose(PoseStack target, Operation<Void> original, @Share("rdi$decorationPose") LocalRef<PoseStack.Pose> decorationPose) {
        PoseStack.Pose previousPose = target.last();
        original.call(target);
        PoseStack.Pose pushedPose = target.last();
        if (pushedPose != previousPose) {
            decorationPose.set(pushedPose);
        }
    }
}
