package calebxzhou.rdi.mc.server.mixin;

import calebxzhou.rdi.mc.server.network.RServerPersistentPackets;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Forge routes every login answer to its own wrapper channel, so the persistent packet answer is taken first.
@Mixin(ServerLoginPacketListenerImpl.class)
abstract class mPersistentPacketQuery {
    @Shadow
    @Final
    Connection connection;

    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void RDI$AnswerPersistentPackets(ServerboundCustomQueryPacket packet, CallbackInfo ci) {
        String name = ((ServerLoginPacketListenerImpl) (Object) this).getUserName();
        if (RServerPersistentPackets.onQueryAnswer(connection, packet, name)) {
            ci.cancel();
        }
    }
}
