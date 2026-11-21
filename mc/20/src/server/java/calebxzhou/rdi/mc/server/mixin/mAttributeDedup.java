package calebxzhou.rdi.mc.server.mixin;

import calebxzau.rdi.mc.server.attributes.AttributeDedupHolder;
import calebxzau.rdi.mc.v20.server.attributes.AttributeDedup20;
import calebxzau.rdi.mc.v20.server.attributes.AttributeDedupState;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips attribute snapshots the client already holds; every play packet passes through Connection.send. */
@Mixin(Connection.class)
public abstract class mAttributeDedup implements AttributeDedupHolder {
    @Unique
    private final AttributeDedupState rdi$attributeDedup = AttributeDedup20.newState();

    @Shadow
    public abstract PacketListener getPacketListener();

    @Shadow
    public abstract void send(Packet<?> packet, @Nullable PacketSendListener listener);

    @Override
    public AttributeDedupState rdi$attributeDedup() {
        return rdi$attributeDedup;
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), cancellable = true)
    private void rdi$dedupAttributes(Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo ci) {
        // The listener is installed on the connection before its constructor assigns the player.
        if (!(getPacketListener() instanceof ServerGamePacketListenerImpl game) || game.player == null) return;
        boolean serverThread = game.player.server.isSameThread();
        // A trimmed packet re-enters here and is already recorded as sent.
        if (serverThread && rdi$attributeDedup.forwarding) return;
        Packet<?> result = AttributeDedup20.process(rdi$attributeDedup, packet, listener != null, serverThread);
        if (result == packet) return;
        ci.cancel();
        if (result == null) return;
        rdi$attributeDedup.forwarding = true;
        try {
            send(result, listener);
        } finally {
            rdi$attributeDedup.forwarding = false;
        }
    }
}
