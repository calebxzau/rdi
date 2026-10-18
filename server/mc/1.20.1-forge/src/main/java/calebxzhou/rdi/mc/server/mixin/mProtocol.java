package calebxzhou.rdi.mc.server.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerLoginPacketListenerImpl.class)
abstract class mServerLoginPacketListener {
    @Redirect(
            method = "handleCustomQueryPacket",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerLoginPacketListenerImpl;disconnect(Lnet/minecraft/network/chat/Component;)V"
            )
    )
    private void RDI$IgnoreUnexpCusQuery(ServerLoginPacketListenerImpl disconnect, Component reason) {
    }

    // 120 seconds instead of 30: on a slow link the Forge registry sync alone can take longer.
    // Optional and chainable, since network mods such as Connectivity modify the same constant.
    @ModifyExpressionValue(method = "tick", at = @At(value = "CONSTANT", args = "intValue=600"), require = 0)
    private int RDI$LoginTimeoutTicks(int ticks) {
        return 2400;
    }
}
