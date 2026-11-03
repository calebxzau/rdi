package calebxzhou.rdi.mc.client.mixin;

import calebxzau.mc.common2021.RdiDevWindow;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev clients only: {@link RMixinPlugin} applies this when {@code rdi.dev.window} is set. */
@Mixin(Window.class)
public class mDevWindow {
    @Shadow @Final private long window;
    @Shadow private int x;
    @Shadow private int y;
    @Shadow private int windowedX;
    @Shadow private int windowedY;

    @Inject(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J"
            )
    )
    private void rdi$prepareHints(CallbackInfo ci) {
        RdiDevWindow.prepareHints();
    }

    @Inject(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwMakeContextCurrent(J)V")
    )
    private void rdi$moveToBackground(CallbackInfo ci) {
        if (!RdiDevWindow.isBackground()) return;
        this.windowedX = this.x = RdiDevWindow.backgroundX();
        this.windowedY = this.y = RdiDevWindow.backgroundY();
        GLFW.glfwSetWindowPos(this.window, this.x, this.y);
        GLFW.glfwShowWindow(this.window);
    }
}
