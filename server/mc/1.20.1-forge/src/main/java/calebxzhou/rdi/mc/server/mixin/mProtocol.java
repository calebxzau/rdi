package calebxzhou.rdi.mc.server.mixin;

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
}
