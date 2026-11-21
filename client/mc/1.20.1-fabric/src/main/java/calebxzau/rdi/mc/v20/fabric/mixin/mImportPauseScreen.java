package calebxzau.rdi.mc.v20.fabric.mixin;

import calebxzau.rdi.mc.v20.fabric.syncchunk.SyncChunkIntegratedFabric;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
abstract class mImportPauseScreen extends Screen {
    protected mImportPauseScreen(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void rdi$disableLanInImportMenu(CallbackInfo callback) {
        if (!SyncChunkIntegratedFabric.getMarking()) return;
        for (var child : children()) {
            if (child instanceof AbstractWidget widget
                    && widget.getMessage().getContents() instanceof TranslatableContents text
                    && text.getKey().equals("menu.shareToLan")) {
                widget.active = false;
            }
        }
    }
}
