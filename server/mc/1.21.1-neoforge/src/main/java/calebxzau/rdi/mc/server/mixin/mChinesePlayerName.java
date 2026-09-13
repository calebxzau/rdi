package calebxzau.rdi.mc.server.mixin;

import net.minecraft.util.StringUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * calebxzhou @ 2025-08-05 11:26
 */
@Mixin(StringUtil.class)
public class mChinesePlayerName {
    @Overwrite
    public static boolean isValidPlayerName(String pUsername) {
        return pUsername.chars().filter((p_203791_) -> (p_203791_ <= 32 || p_203791_ >= 127) && (p_203791_ < 0x4E00 || p_203791_ > 0x9FFF)).findAny().isEmpty();
    }
}
