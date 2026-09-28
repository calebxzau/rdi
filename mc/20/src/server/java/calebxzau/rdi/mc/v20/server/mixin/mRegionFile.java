package calebxzau.rdi.mc.v20.server.mixin;

import calebxzau.rdi.mc.v20.server.region.RegionZstdCodec;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.io.DataOutputStream;
import java.io.OutputStream;

@Mixin(RegionFile.class)
public abstract class mRegionFile {
    @ModifyExpressionValue(
        method = "<init>(Ljava/nio/file/Path;Ljava/nio/file/Path;Z)V",
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/chunk/storage/RegionFileVersion;VERSION_DEFLATE:Lnet/minecraft/world/level/chunk/storage/RegionFileVersion;")
    )
    private static RegionFileVersion rdi$selectZstd(RegionFileVersion original) {
        return RegionZstdCodec.register();
    }

    @WrapOperation(
        method = "getChunkDataOutputStream",
        at = @At(value = "NEW", target = "(Ljava/io/OutputStream;)Ljava/io/DataOutputStream;")
    )
    private DataOutputStream rdi$wrapId8Output(OutputStream output, Operation<DataOutputStream> original) {
        return RegionZstdCodec.wrapDataOutput(output, original.call(output));
    }
}
