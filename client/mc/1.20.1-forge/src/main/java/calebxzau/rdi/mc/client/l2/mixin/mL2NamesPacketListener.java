package calebxzau.rdi.mc.client.l2.mixin;

import calebxzau.rdi.mc.client.l2.RClientL2Names;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class mL2NamesPacketListener {
    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void rdi$handleL2Names(
        ClientboundCustomPayloadPacket packet,
        CallbackInfo ci
    ) {
        ClientPacketListener listener = (ClientPacketListener) (Object) this;
        if (!RClientL2Names.shouldIntercept(listener, packet)) {
            return;
        }

        // Vanilla handles Forge payloads before its thread check. Schedule this payload first so
        // cached names are applied in the same order as attribute updates on the client thread.
        PacketUtils.ensureRunningOnSameThread(packet, listener, Minecraft.getInstance());
        if (RClientL2Names.handleCustomPayload(listener, packet)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleUpdateAttributes", at = @At("RETURN"))
    private void rdi$restoreL2ModifierNames(
        ClientboundUpdateAttributesPacket packet,
        CallbackInfo ci
    ) {
        RClientL2Names.afterAttributeUpdate((ClientPacketListener) (Object) this, packet);
    }

    @Inject(method = "handleLogin", at = @At("RETURN"))
    private void rdi$clearL2NamesAfterLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        RClientL2Names.clearAfterLogin((ClientPacketListener) (Object) this);
    }

    @Inject(method = "handleRespawn", at = @At("RETURN"))
    private void rdi$clearL2NamesAfterRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        RClientL2Names.clearAfterRespawn((ClientPacketListener) (Object) this);
    }

    @Inject(method = "handleRemoveEntities", at = @At("RETURN"))
    private void rdi$clearRemovedLocalPlayerNames(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
        RClientL2Names.clearIfLocalPlayerRemoved(
            (ClientPacketListener) (Object) this,
            packet.getEntityIds().toIntArray()
        );
    }
}
