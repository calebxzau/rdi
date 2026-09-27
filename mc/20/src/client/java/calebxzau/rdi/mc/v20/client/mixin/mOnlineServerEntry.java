package calebxzau.rdi.mc.v20.client.mixin;

import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class mOnlineServerEntry {
    @ModifyArg(
            method = "render",
            index = 1,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;",
                    ordinal = 0
            )
    )
    private Object[] rdi$displayFixedPing(Object[] translatableArgs) {
        translatableArgs[0] = 11L;
        return translatableArgs;
    }

    @ModifyArg(
            method = "getNarration",
            index = 1,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;",
                    ordinal = 3
            )
    )
    private Object[] rdi$narrateFixedPing(Object[] translatableArgs) {
        translatableArgs[0] = 11L;
        return translatableArgs;
    }
}
