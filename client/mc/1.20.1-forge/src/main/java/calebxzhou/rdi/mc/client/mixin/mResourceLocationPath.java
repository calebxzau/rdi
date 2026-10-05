package calebxzhou.rdi.mc.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.atomic.AtomicLong;

@Mixin(FallbackResourceManager.class)
public abstract class mResourceLocationPath {
    @Unique
    private final AtomicLong rdi$nextInvalidPathWarning = new AtomicLong();

    @WrapOperation(
            method = {"listResources", "listPackResources"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/PackResources;listResources(Lnet/minecraft/server/packs/PackType;Ljava/lang/String;Ljava/lang/String;Lnet/minecraft/server/packs/PackResources$ResourceOutput;)V")
    )
    private void rdi$skipInvalidResourcePaths(PackResources pack, PackType type, String namespace,
                                             String path, PackResources.ResourceOutput output,
                                             Operation<Void> original) {
        original.call(pack, type, namespace, path, (PackResources.ResourceOutput) (location, supplier) -> {
            // Validate before metadata lookup calls withPath; do not change ResourceLocation semantics.
            if (!location.getPath().chars().allMatch(character -> character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9' || character == '/'
                    || character == '.' || character == '_' || character == '-')) {
                long now = System.currentTimeMillis();
                long nextWarning = rdi$nextInvalidPathWarning.get();
                if (now >= nextWarning && rdi$nextInvalidPathWarning.compareAndSet(nextWarning, now + 10_000L)) {
                    LogUtils.getLogger().warn("Skipping resource with invalid path {} from pack {} (warnings limited to once per 10 seconds)",
                            location, pack.packId());
                }
                return;
            }
            output.accept(location, supplier);
        });
    }
}
