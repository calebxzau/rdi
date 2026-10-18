package calebxzau.rdi.mc.server.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A client frozen while loading in sends nothing but keep-alive answers, so the 30 second read
 * timeout would close it before the 120 second keep-alive timeout in {@link mKeepAliveTimeout} applies.
 * Optional, since network mods such as Connectivity may replace the read timeout handler themselves.
 */
@Mixin(targets = "net.minecraft.server.network.ServerConnectionListener$1")
public abstract class mReadTimeout {
    @Unique
    private static final int RDI$READ_TIMEOUT_SECONDS = 120;

    @ModifyExpressionValue(
            method = "initChannel",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/server/network/ServerConnectionListener;READ_TIMEOUT:I",
                    opcode = Opcodes.GETSTATIC
            ),
            require = 0
    )
    private int RDI$readTimeout(int seconds) {
        return RDI$READ_TIMEOUT_SECONDS;
    }
}
