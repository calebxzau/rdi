package calebxzau.rdi.mc.server.mixin;

import calebxzau.rdi.mc.server.region.RegionZstdCodec;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.io.DataOutputStream;
import java.io.OutputStream;

@Mixin(RegionFile.class)
public abstract class mRegionFile {
    @WrapOperation(
        method = "getChunkDataOutputStream",
        at = @At(value = "NEW", target = "(Ljava/io/OutputStream;)Ljava/io/DataOutputStream;")
    )
    private DataOutputStream rdi$wrapId8Output(OutputStream output, Operation<DataOutputStream> original) {
        return RegionZstdCodec.wrapDataOutput(output, original.call(output));
    }
}
