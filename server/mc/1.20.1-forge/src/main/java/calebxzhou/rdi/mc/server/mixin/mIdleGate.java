package calebxzhou.rdi.mc.server.mixin;

import calebxzhou.rdi.mc.server.network.RServerIdle;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A send with a listener expects completion, so only listener-free sends are held back.
@Mixin(ServerGamePacketListenerImpl.class)
abstract class mIdleGate {
    @Shadow
    public ServerPlayer player;

    @Inject(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void RDI$HoldBackWhileAway(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        if (listener == null && RServerIdle.intercept(player, packet)) {
            ci.cancel();
        }
    }
}
