package calebxzhou.rdi.mc.client.mixin;

import net.neoforged.neoforge.client.loading.ClientModLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = ClientModLoader.class, remap = false)
public abstract class mLoadingWarnings {
    /**
     * Keep NeoForge's built-in warning logging while allowing the earlier fatal-error branch to show its screen.
     */
    @ModifyVariable(
            method = "completeModLoading(Ljava/lang/Runnable;)Ljava/lang/Runnable;",
            at = @At("LOAD"),
            ordinal = 0,
            require = 1,
            allow = 1,
            remap = false
    )
    private static boolean rdi$hideLoadingWarnings(boolean showWarnings) {
        return false;
    }
}
