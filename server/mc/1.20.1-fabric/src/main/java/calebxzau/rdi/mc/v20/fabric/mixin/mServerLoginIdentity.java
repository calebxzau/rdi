package calebxzau.rdi.mc.v20.fabric.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(ServerLoginPacketListenerImpl.class)
abstract class mServerLoginIdentity {
    @Shadow
    public abstract void disconnect(Component reason);

    @Unique
    private ServerboundHelloPacket rdi$helloPacket;

    @Inject(method = "handleHello", at = @At("HEAD"))
    private void RDI$CaptureHello(ServerboundHelloPacket packet, CallbackInfo ci) {
        this.rdi$helloPacket = packet;
    }

    @Redirect(
            method = "handleHello",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;getSingleplayerProfile()Lcom/mojang/authlib/GameProfile;"
            )
    )
    private GameProfile RDI$UseSubmittedIdentity(MinecraftServer server) {
        Optional<java.util.UUID> profileId = this.rdi$helloPacket.profileId();
        if (profileId.isEmpty()) {
            this.disconnect(Component.literal("未登录RDI账号！"));
            return null;
        }
        return new GameProfile(profileId.get(), this.rdi$helloPacket.name());
    }
}
