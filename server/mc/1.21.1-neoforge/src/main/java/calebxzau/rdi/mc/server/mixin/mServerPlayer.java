package calebxzau.rdi.mc.server.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * calebxzhou @ 2026-03-01 15:44
 */
@Mixin(ServerPlayer.class)
public class mServerPlayer {
    //不开启搜不到在线信息
    @Overwrite
    public boolean allowsListing() {
        return true;
    }
}
