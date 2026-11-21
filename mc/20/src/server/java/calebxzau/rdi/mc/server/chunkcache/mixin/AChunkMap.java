package calebxzau.rdi.mc.server.chunkcache.mixin;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets warm-up send a held chunk later through the same vanilla path that would have sent it. */
@Mixin(ChunkMap.class)
public interface AChunkMap {
    @Accessor("viewDistance")
    int rdi$viewDistance();

    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder rdi$getVisibleChunkIfPresent(long chunkPos);

    @Invoker("playerLoadedChunk")
    void rdi$playerLoadedChunk(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packetCache, LevelChunk chunk);
}
