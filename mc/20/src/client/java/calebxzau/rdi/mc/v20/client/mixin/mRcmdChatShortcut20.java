package calebxzau.rdi.mc.v20.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class mRcmdChatShortcut20 {
    @Unique
    private static final KeyMapping RDI$RCMD_KEY = new KeyMapping(
            "key.rdi.rcmd",
            InputConstants.KEY_BACKSLASH,
            "key.categories.multiplayer"
    );

    @Shadow
    @Nullable
    public Screen screen;

    @Shadow
    @Nullable
    private Overlay overlay;

    @Shadow
    private void openChatScreen(String defaultText) {
    }

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void rdi$openRcmdChat(CallbackInfo ci) {
        while (RDI$RCMD_KEY.consumeClick()) {
            if (this.screen == null && this.overlay == null) {
                this.openChatScreen("\\");
            }
        }
    }
}
