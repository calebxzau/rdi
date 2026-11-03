package calebxzhou.rdi.mc.client.mixin;

import calebxzau.mc.common2021.RdiDevWindow;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Dev clients only: {@link RMixinPlugin} applies this when {@code rdi.dev.window} is set. Mixin 0.8.5 rejects
 * {@code @Inject} on a constructor's INVOKE, and a redirect could clash with another mod in a player's game.
 * Embeddium already redirects {@code setupMinecraftWindow}, so the hints go in right after the default ones.
 */
@Mixin(Window.class)
public class mDevWindow {
    @Shadow private int x;
    @Shadow private int y;
    @Shadow private int windowedX;
    @Shadow private int windowedY;

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwDefaultWindowHints()V", remap = false)
    )
    private void rdi$prepareHints() {
        GLFW.glfwDefaultWindowHints();
        RdiDevWindow.prepareHints();
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwMakeContextCurrent(J)V", remap = false)
    )
    private void rdi$moveToBackground(long window) {
        if (RdiDevWindow.isBackground()) {
            this.windowedX = this.x = RdiDevWindow.backgroundX();
            this.windowedY = this.y = RdiDevWindow.backgroundY();
            GLFW.glfwSetWindowPos(window, this.x, this.y);
            GLFW.glfwShowWindow(window);
        }
        GLFW.glfwMakeContextCurrent(window);
    }
}
