package calebxzhou.rdi.mc.server.mixin;

import calebxzau.rdi.mc.server.compat.XiaoxiangButtonIds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 纳虚天工镜「自动输入／自动输出」: restores button ids 262/263 that the byte-sized vanilla packet
 * truncated to 6/7. Applied only while the menu still uses the wide ids (see {@link RMixinPlugin}).
 */
@Pseudo
@Mixin(targets = "com.xiaoxiang.cultivation.inventory.StorageNetworkMenu", remap = false)
public abstract class mXiaoxiangStorageButtons {
    @ModifyVariable(
            method = "m_6366_(Lnet/minecraft/world/entity/player/Player;I)Z",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            remap = false
    )
    private int rdi$restoreButtonId(int buttonId) {
        return XiaoxiangButtonIds.storageButton(buttonId);
    }
}
