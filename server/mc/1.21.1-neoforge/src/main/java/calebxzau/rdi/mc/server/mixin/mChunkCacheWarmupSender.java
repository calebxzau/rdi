package calebxzau.rdi.mc.server.mixin;

import calebxzau.rdi.mc.server.chunkcache.ChunkCacheServerService;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * While a chunk cache session warms up, sends only the chunks the client has offered or declined (plus those next to
 * the player) and leaves the rest pending, as vanilla does with chunks that are not ready.
 */
@Mixin(PlayerChunkSender.class)
public abstract class mChunkCacheWarmupSender {
    @Shadow @Final private LongSet pendingChunks;
    @Shadow @Final private boolean memoryConnection;
    @Shadow private float batchQuota;

    /** The sender belongs to one connection; this is the player of the current {@code sendNextChunks} call. */
    @Unique
    private ServerPlayer rdi$player;

    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void rdi$rememberPlayer(ServerPlayer player, CallbackInfo callback) {
        rdi$player = player;
    }

    @Inject(method = "collectChunksToSend", at = @At("HEAD"), cancellable = true)
    private void rdi$collectWarmupChunks(ChunkMap chunkMap, ChunkPos chunkPos, CallbackInfoReturnable<List<LevelChunk>> callback) {
        if (memoryConnection || rdi$player == null) return;
        List<LevelChunk> chunks = ChunkCacheServerService.selectWarmup(rdi$player, pendingChunks, Mth.floor(batchQuota), chunkPos, chunkMap);
        if (chunks == null) return;
        for (LevelChunk chunk : chunks) {
            pendingChunks.remove(chunk.getPos().toLong());
        }
        callback.setReturnValue(chunks);
    }
}
