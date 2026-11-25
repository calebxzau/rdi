package calebxzhou.rdi.mc.server.mixin;

import calebxzau.rdi.mc.server.compat.XiaoxiangButtonIds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 炼丹炉／炼器炉「自动运作」: restores button id 261 that the byte-sized vanilla packet truncated to 5.
 * Applied only while the menu still uses the wide id (see {@link RMixinPlugin}).
 */
@Pseudo
@Mixin(
        targets = {
                "com.xiaoxiang.cultivation.inventory.AlchemyMenu",
                "com.xiaoxiang.cultivation.inventory.RefiningMenu"
        },
        remap = false
)
public abstract class mXiaoxiangFurnaceButtons {
    @ModifyVariable(
            method = "m_6366_(Lnet/minecraft/world/entity/player/Player;I)Z",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            remap = false
    )
    private int rdi$restoreButtonId(int buttonId) {
        return XiaoxiangButtonIds.furnaceButton(buttonId);
    }
}
