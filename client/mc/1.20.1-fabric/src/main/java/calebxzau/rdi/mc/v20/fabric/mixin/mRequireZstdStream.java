package calebxzau.rdi.mc.v20.fabric.mixin;

import calebxzau.rdi.mc.v20.fabric.network.FabricPacketExtensionsClient;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientHandshakePacketListenerImpl.class)
abstract class mRequireZstdStream {
    @Shadow @Final private Connection connection;

    @Inject(method = "handleGameProfile", at = @At("HEAD"), cancellable = true)
    private void rdi$requireStream(ClientboundGameProfilePacket packet, CallbackInfo ci) {
        if (!FabricPacketExtensionsClient.requireStream(connection)) ci.cancel();
    }
}
