package calebxzhou.rdi.mc.client.mixin;

import calebxzhou.rdi.mc.common.RDI;
import calebxzau.rdi.mc.client.dm.DmUi;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static calebxzhou.rdi.mc.client.RDIMain.JOIN_BUTTON;
import static calebxzhou.rdi.mc.client.RDIMain.layoutJoinButton;

/**
 * calebxzhou @ 2026-01-26 20:41
 */
@Mixin(JoinMultiplayerScreen.class)
public class mMultiScreen extends Screen {
    @Shadow
    private ServerList servers;

    protected mMultiScreen(Component title) {
        super(title);
    }

    @Inject(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ServerList;load()V",
                    shift = At.Shift.AFTER
            )
    )
    private void RDI$UseLocalServerList(CallbackInfo ci) {
        while (this.servers.size() > 0) {
            this.servers.remove(this.servers.get(0));
        }
        this.servers.add(new ServerData(RDI.HOST_NAME, "127.0.0.1:55667", ServerData.Type.OTHER), false);
        this.servers.save();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void RDI$JoinButton(CallbackInfo ci) {
        layoutJoinButton(this.width);
        this.addRenderableWidget(JOIN_BUTTON);
        if (DmUi.isEnabled()) {
            this.addRenderableWidget(DmUi.createJoinButton(this.width));
        }
    }
}
