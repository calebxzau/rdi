package calebxzhou.rdi.mc.server.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FallbackResourceManager.class)
public abstract class mFallbackResourceManager {
    @Shadow
    @Final
    private static Logger LOGGER;

    @WrapOperation(
        method = {"listResources", "listPackResources"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/PackResources;listResources(Lnet/minecraft/server/packs/PackType;Ljava/lang/String;Ljava/lang/String;Lnet/minecraft/server/packs/PackResources$ResourceOutput;)V"),
        require = 2,
        allow = 2
    )
    private void rdi$skipInvalidDataPackPaths(
        PackResources pack, PackType type, String namespace, String path,
        PackResources.ResourceOutput output, Operation<Void> original
    ) {
        if (type != PackType.SERVER_DATA) {
            original.call(pack, type, namespace, path, output);
            return;
        }

        PackResources.ResourceOutput validatedOutput = (location, resource) -> {
            if (ResourceLocation.isValidPath(location.getPath())) {
                output.accept(location, resource);
            } else {
                LOGGER.warn("Skipping invalid data pack resource path {} in pack {}", location, pack.packId());
            }
        };
        original.call(pack, type, namespace, path, validatedOutput);
    }
}
