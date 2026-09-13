package calebxzau.rdi.mc.server.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * calebxzhou @ 2024-05-31 20:09
 */
@Mixin(ServerboundHelloPacket.class)
abstract class mAllowChineseNameLogin {

    @ModifyConstant(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", constant = @Constant(intValue = 16))
    private static int RDI$AllowChineseName(int constant) {
        return 64;
    }

}
/**
 * calebxzhou @ 2025-02-21 0:32
 */
@Mixin(ServerLoginPacketListenerImpl.class)
class mServerLoginPacketListener {
    @Shadow
    @Nullable
    @Mutable
    private GameProfile authenticatedProfile;

    //注入rdid到mc本体的uuid
    @Inject(method = "handleHello", at = @At(value = "TAIL"))
    private void injectUUID(ServerboundHelloPacket pPacket, CallbackInfo ci) {
        authenticatedProfile = new GameProfile(pPacket.profileId(), pPacket.name());
    }
}
