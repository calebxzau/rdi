package calebxzau.rdi.mc.server.mixin;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerChunkSender.class)
public interface APlayerChunkSender {
    @Accessor("pendingChunks")
    LongSet rdi$pendingChunks();
}
