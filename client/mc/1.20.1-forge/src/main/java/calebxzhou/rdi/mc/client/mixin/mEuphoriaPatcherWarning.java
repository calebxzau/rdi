package calebxzhou.rdi.mc.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(
        targets = "com.euphoriapatches.euphoria_patcher.util.UserInstallErrorMessages",
        remap = false
)
public abstract class mEuphoriaPatcherWarning {
    // Euphoria moved the comparator package; optional suppression must not block launch.
    @Redirect(
            method = {
                    "handleShaderNotFound(Lcom/euphoriapatches/euphoria_patcher/util/ShaderVersionComparator;)V",
                    "handleShaderNotFound(Lcom/euphoriapatches/euphoria_patcher/util/shader/ShaderVersionComparator;)V"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/euphoriapatches/euphoria_patcher/EuphoriaPatcher;log(IILjava/lang/String;)V",
                    remap = false
            ),
            require = 0,
            expect = 0,
            remap = false
    )
    private static void rdi$ignoreEuphoriaLog(int messageLevel, int messageFadeTimer, String message) {
    }

    @Redirect(
            method = {
                    "handleShaderNotFound(Lcom/euphoriapatches/euphoria_patcher/util/ShaderVersionComparator;)V",
                    "handleShaderNotFound(Lcom/euphoriapatches/euphoria_patcher/util/shader/ShaderVersionComparator;)V"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/euphoriapatches/euphoria_patcher/util/UserInstallErrorMessages;copyLinkMessage()V",
                    remap = false
            ),
            require = 0,
            expect = 0,
            remap = false
    )
    private static void rdi$ignoreCopyLinkMessage() {
    }
}
