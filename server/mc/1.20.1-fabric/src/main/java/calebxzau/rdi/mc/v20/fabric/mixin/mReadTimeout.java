package calebxzau.rdi.mc.v20.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Aligns the vanilla read timeout with the shared keep-alive timeout during joins. */
@Mixin(targets = "net.minecraft.server.network.ServerConnectionListener$1")
abstract class mReadTimeout {
    @ModifyExpressionValue(method = "initChannel(Lio/netty/channel/Channel;)V",
            at = @At(value = "CONSTANT", args = "intValue=30"), require = 0, remap = false)
    private int rdi$readTimeout(int seconds) {
        return 120;
    }
}
