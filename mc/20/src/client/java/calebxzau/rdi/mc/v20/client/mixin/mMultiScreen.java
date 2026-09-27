package calebxzau.rdi.mc.v20.client.mixin;

import calebxzhou.rdi.mc.common.RDI;
import net.minecraft.client.gui.components.Button;
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

import static calebxzau.rdi.mc.v20.client.RoomJoinUi20.JOIN_BUTTON;

@Mixin(JoinMultiplayerScreen.class)
public abstract class mMultiScreen extends Screen {
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
    private void rdi$useLocalServerList(CallbackInfo ci) {
        while (this.servers.size() > 0) {
            this.servers.remove(this.servers.get(0));
        }
        this.servers.add(new ServerData(RDI.HOST_NAME, "127.0.0.1:55667", false), false);
        this.servers.save();
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void rdi$addRoomButtons(CallbackInfo ci) {
        int bottom = this.height - 64;
        if (bottom <= 0) {
            return;
        }

        int buttonCount = (bottom + 49) / 50;
        int buttonX = this.width / 2 - 250;
        for (int index = 0; index < buttonCount; index++) {
            int buttonTop = index * bottom / buttonCount;
            int buttonBottom = (index + 1) * bottom / buttonCount;
            this.addRenderableWidget(Button.builder(
                    JOIN_BUTTON.getMessage(),
                    ignored -> JOIN_BUTTON.onPress()
            ).bounds(buttonX, buttonTop, 500, buttonBottom - buttonTop).build());
        }
    }
}
