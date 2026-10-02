package calebxzau.rdi.mc.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;

/** Chunk cache updates are captured from detached server packets; no live-level hooks are required. */
@Mixin(ClientLevel.class)
public abstract class mChunkCacheLevel {
}
