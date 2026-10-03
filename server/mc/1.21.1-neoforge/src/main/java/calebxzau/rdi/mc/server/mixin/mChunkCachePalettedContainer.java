package calebxzau.rdi.mc.server.mixin;

import calebxzau.rdi.mc.server.chunkcache.ChunkCacheContainerStamp;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PalettedContainer.class)
public abstract class mChunkCachePalettedContainer implements ChunkCacheContainerStamp {
    // Plain int: these setters are hot during worldgen. Vanilla writes live chunks only on the server
    // thread (ProtoChunk promotion orders earlier writes); mods writing live chunks off-thread are
    // unsupported and are caught by ServerTerrainHashes' sampled verification, which fails closed.
    @Unique private int rdi$modCount;

    @Override
    public int rdi$modCount() {
        return rdi$modCount;
    }

    // Incremented before the write (covers writes that throw partway) and after it lands, so a hash
    // computed from a stamp read before either point is never reused.
    @Inject(method = {
            "set(IIILjava/lang/Object;)V",
            "read(Lnet/minecraft/network/FriendlyByteBuf;)V"
    }, at = {@At("HEAD"), @At("RETURN")})
    private void rdi$contentChanged(CallbackInfo ci) {
        rdi$modCount++;
    }

    @Inject(method = {
            "getAndSet(IIILjava/lang/Object;)Ljava/lang/Object;",
            "getAndSetUnchecked(IIILjava/lang/Object;)Ljava/lang/Object;"
    }, at = {@At("HEAD"), @At("RETURN")})
    private void rdi$contentSwapped(CallbackInfoReturnable<Object> cir) {
        rdi$modCount++;
    }
}
