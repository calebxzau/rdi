package calebxzau.rdi.mc.server.mixin;

import calebxzau.rdi.mc.server.region.RegionZstdCodec;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.io.DataOutput;
import java.io.IOException;

@Mixin(RegionFileStorage.class)
public abstract class mRegionFileStorage {
    @WrapOperation(
        method = "write",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/NbtIo;write(Lnet/minecraft/nbt/CompoundTag;Ljava/io/DataOutput;)V")
    )
    private void rdi$writeNbtWithAbort(CompoundTag chunkData, DataOutput output, Operation<Void> original)
        throws IOException {
        try {
            original.call(chunkData, output);
        } catch (Throwable failure) {
            // Only the ID8 wrapper is abortable. Vanilla codecs retain their
            // original close behavior and exception flow.
            RegionZstdCodec.abort(output);
            rdi$rethrow(failure);
        }
    }

    @Unique
    private static void rdi$rethrow(Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IOException("Unexpected failure while serializing chunk NBT", failure);
    }
}
