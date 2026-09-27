package calebxzau.rdi.mc.v20.fabric.mixin;

import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(ServerboundHelloPacket.class)
abstract class mServerboundHelloPacket {
    @ModifyConstant(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", constant = @org.spongepowered.asm.mixin.injection.Constant(intValue = 16))
    private static int RDI$AllowLongerName(int constant) {
        return 64;
    }
}
