package calebxzhou.rdi.mc.server.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keep-alive challenges still go out every 15 seconds, but the client has 120 seconds to answer one.
 * On a slow link the join burst queues ahead of the challenge, so vanilla's 15 second answer window
 * times players out while they are still loading in. Chains with network mods such as Connectivity that
 * modify the same constant; optional, so a mod that still blocks it only leaves their timeout in place.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class mPlusTimeout {
    @Unique
    private static final long RDI$KEEP_ALIVE_TIMEOUT_MILLIS = 120_000L;

    @Shadow
    private boolean keepAlivePending;

    @ModifyExpressionValue(method = "tick", at = @At(value = "CONSTANT", args = "longValue=15000"), require = 0)
    private long RDI$keepAliveTimeout(long interval) {
        return keepAlivePending ? RDI$KEEP_ALIVE_TIMEOUT_MILLIS : interval;
    }
}
