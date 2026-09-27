package calebxzau.rdi.mc.v20.client.mixin;

import calebxzau.rdi.mc.v20.client.MissingImageFallback;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.io.FileNotFoundException;

@Mixin(ResourceProvider.class)
public interface mMissingImageResource extends ResourceProvider {
    /**
     * Interface injections are unavailable in Forge's Mixin 0.8.5.
     *
     * @author RDI
     * @reason Recover missing images only at mandatory resource reads, preserving optional probes.
     */
    @Overwrite
    @Override
    default Resource getResourceOrThrow(ResourceLocation location) throws FileNotFoundException {
        return MissingImageFallback.getResourceOrThrow(this, location);
    }
}
