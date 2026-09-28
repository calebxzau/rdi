package calebxzau.rdi.mc.v20.client.mixin;

import calebxzau.rdi.mc.v20.client.KeyboardInputGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(KeyboardHandler.class)
public abstract class mKeyboardInputGuard {
    @Shadow @Final private Minecraft minecraft;

    @WrapMethod(method = "keyPress(JIIII)V")
    private void rdi$guardKeyPress(long window, int key, int scanCode, int action, int modifiers,
                                   Operation<Void> original) {
        boolean completed = KeyboardInputGuard.guard("keyPress", rdi$screenName(),
                "key=" + key + ", scanCode=" + scanCode + ", action=" + action + ", modifiers=" + modifiers,
                () -> original.call(window, key, scanCode, action, modifiers));
        // A screen may close and then throw before vanilla clears the released key.
        if (!completed && action == GLFW.GLFW_RELEASE && minecraft.screen == null
                && window == minecraft.getWindow().getWindow()) {
            KeyboardInputGuard.guard("keyReleaseRecovery", rdi$screenName(), "key=" + key,
                    () -> KeyMapping.set(InputConstants.getKey(key, scanCode), false));
        }
    }

    @WrapMethod(method = "charTyped(JII)V")
    private void rdi$guardCharTyped(long window, int codePoint, int modifiers, Operation<Void> original) {
        KeyboardInputGuard.guard("charTyped", rdi$screenName(),
                "modifiers=" + modifiers,
                () -> original.call(window, codePoint, modifiers));
    }

    @Unique
    private String rdi$screenName() {
        return minecraft.screen == null ? "<in-game>" : minecraft.screen.getClass().getName();
    }
}
