package calebxzau.rdi.mc.server.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerChunkSender.class)
public abstract class mChunkCacheSender {
    @WrapOperation(
            method = "sendChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"
            )
    )
    private static void RDI$reuseCachedTerrain(
            ServerGamePacketListenerImpl listener,
            Packet<?> packet,
            Operation<Void> original,
            @Local(argsOnly = true) LevelChunk chunk
    ) {
        Packet<?> outgoing = calebxzau.rdi.mc.server.chunkcache.ChunkCacheServerService.replace(listener, packet, chunk);
        original.call(listener, outgoing);
        calebxzau.rdi.mc.server.chunkcache.ChunkCacheServerService.afterSend(listener, outgoing);
    }
}
