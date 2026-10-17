package calebxzau.rdi.mc.server.mixin;

import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Keep-alive challenges still go out every 15 seconds, but the client has 120 seconds to answer one.
 * On a slow link the join burst queues ahead of the challenge, so vanilla's 15 second answer window
 * times players out while they are still loading in.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class mKeepAliveTimeout {
    @Unique
    private static final long RDI$KEEP_ALIVE_TIMEOUT_MILLIS = 120_000L;

    @Shadow
    private boolean keepAlivePending;

    @ModifyConstant(method = "keepConnectionAlive", constant = @Constant(longValue = 15000L))
    private long RDI$keepAliveTimeout(long interval) {
        return keepAlivePending ? RDI$KEEP_ALIVE_TIMEOUT_MILLIS : interval;
    }
}
