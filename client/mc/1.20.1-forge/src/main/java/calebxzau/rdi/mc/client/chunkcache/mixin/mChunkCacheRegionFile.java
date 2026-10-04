package calebxzau.rdi.mc.client.chunkcache.mixin;

import calebxzau.rdi.mc.regioncodec.RegionZstdStreams;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.io.DataOutputStream;
import java.io.OutputStream;

@Mixin(RegionFile.class)
public abstract class mChunkCacheRegionFile {
    @WrapOperation(method = "getChunkDataOutputStream",
            at = @At(value = "NEW", target = "(Ljava/io/OutputStream;)Ljava/io/DataOutputStream;"))
    private DataOutputStream rdi$abortableOutput(OutputStream output, Operation<DataOutputStream> original) {
        return RegionZstdStreams.wrapDataOutput(output, original.call(output));
    }
}
