package calebxzau.rdi.mc.server.mixin;

import calebxzau.rdi.mc.server.region.RegionZstdCodec;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RegionFileVersion.class)
public abstract class mRegionFileVersion {
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void rdi$registerZstd(CallbackInfo callbackInfo) {
        RegionZstdCodec.register();
    }

    @Inject(method = "getSelected", at = @At("HEAD"), cancellable = true)
    private static void rdi$selectZstd(CallbackInfoReturnable<RegionFileVersion> callbackInfo) {
        //if (Boolean.parseBoolean(System.getProperty("rdi.zstdChunkSaves", "true"))) {
            callbackInfo.setReturnValue(RegionZstdCodec.register());
       // }
    }
}
