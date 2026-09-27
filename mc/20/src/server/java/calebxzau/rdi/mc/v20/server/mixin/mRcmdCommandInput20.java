package calebxzau.rdi.mc.v20.server.mixin;

import calebxzau.rdi.mc.v20.server.rcmd.RcmdServerRuntime20;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Commands.class)
public class mRcmdCommandInput20 {
    @Inject(method = "performPrefixedCommand", at = @At("HEAD"), cancellable = true)
    private void rdi$dispatchRcmd(CommandSourceStack source, String command, CallbackInfoReturnable<Integer> cir) {
        Integer result = RcmdServerRuntime20.dispatchCommand(source, command);
        if (result != null) cir.setReturnValue(result);
    }
}
