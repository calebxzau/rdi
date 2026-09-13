package calebxzau.rdi.mc.server.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * calebxzhou @ 2025-04-05 18:09
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class mPackListener {
    //玩家移动过快不回弹
    @Redirect(method = "handleMovePlayer",at= @At(ordinal = 1, value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;teleport(DDDFF)V"))
    private void RDI$NeverTpBackForMoveTooQuickly(ServerGamePacketListenerImpl instance, double x, double y, double z, float yaw, float pitch){

    }

    //玩家不回弹
    @Redirect(method = "handleMovePlayer",at= @At(ordinal = 2, value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;teleport(DDDFF)V"))
    private void RDI$NeverTpBack(ServerGamePacketListenerImpl instance, double x, double y, double z, float yaw, float pitch){

    }
}
