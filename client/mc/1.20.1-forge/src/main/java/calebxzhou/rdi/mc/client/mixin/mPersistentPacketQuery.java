package calebxzhou.rdi.mc.client.mixin;

import calebxzhou.rdi.mc.client.network.RClientPersistentPackets;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Answered before Forge looks for a channel; the query is not a Forge login message.
@Mixin(ClientHandshakePacketListenerImpl.class)
abstract class mPersistentPacketQuery {
    @Shadow
    @Final
    private Connection connection;

    @Inject(method = "handleCustomQuery", at = @At("HEAD"), cancellable = true)
    private void RDI$AnswerPersistentPackets(ClientboundCustomQueryPacket packet, CallbackInfo ci) {
        if (RClientPersistentPackets.onQuery(connection, packet)) {
            ci.cancel();
        }
    }
}
