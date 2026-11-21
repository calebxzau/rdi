package calebxzau.rdi.mc.client.chunkcache.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 1.20.1 servers track chunks by their own view distance, which login and radius packets store here. */
@Mixin(ClientPacketListener.class)
public interface AChunkCacheServerChunkRadius {
    @Accessor("serverChunkRadius")
    int rdi$serverChunkRadius();
}
