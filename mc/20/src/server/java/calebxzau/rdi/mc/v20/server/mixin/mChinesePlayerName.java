package calebxzau.rdi.mc.v20.server.mixin;

import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(ServerLoginPacketListenerImpl.class)
abstract class mChinesePlayerName {
    /** @author RDI */
    @Overwrite
    public static boolean isValidUsername(String username) {
        return username.chars()
                .filter(character -> (character <= 32 || character >= 127)
                        && (character < 0x4E00 || character > 0x9FFF))
                .findAny()
                .isEmpty();
    }
}
