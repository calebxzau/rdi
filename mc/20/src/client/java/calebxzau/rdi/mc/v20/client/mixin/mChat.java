package calebxzau.rdi.mc.v20.client.mixin;

import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientPacketListener.class)
public abstract class mChat {
    @Redirect(
            method = "handleServerData",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/toasts/ToastComponent;addToast(Lnet/minecraft/client/gui/components/toasts/Toast;)V"
            )
    )
    private void rdi$hideUnsecureChatWarning(ToastComponent toasts, Toast toast) {
        if (toast instanceof SystemToast systemToast
                && systemToast.getToken() == SystemToast.SystemToastIds.UNSECURE_SERVER_WARNING) {
            return;
        }
        toasts.addToast(toast);
    }
}
