package calebxzau.rdi.mc.v20.server.mixin;

import calebxzau.rdi.mc.v20.server.region.RegionZstdCodec;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RegionFileVersion.class)
public abstract class mRegionFileVersion {
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void rdi$registerZstd(CallbackInfo callbackInfo) {
        RegionZstdCodec.register();
    }
}
