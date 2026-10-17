package calebxzhou.rdi.mc.server.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Keep-alive challenges still go out every 15 seconds, but the client has 120 seconds to answer one.
 * On a slow link the join burst queues ahead of the challenge, so vanilla's 15 second answer window
 * times players out while they are still loading in. Network mods such as Connectivity may already
 * own this constant; their timeout then applies instead, so the injection is optional.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class mPlusTimeout {
    @Unique
    private static final long RDI$KEEP_ALIVE_TIMEOUT_MILLIS = 120_000L;

    @Shadow
    private boolean keepAlivePending;

    @ModifyConstant(method = "tick", constant = @Constant(longValue = 15000L), require = 0)
    private long RDI$keepAliveTimeout(long interval) {
        return keepAlivePending ? RDI$KEEP_ALIVE_TIMEOUT_MILLIS : interval;
    }
}
