package calebxzau.rdi.mc.client.mixin;

import java.nio.IntBuffer;
import java.nio.file.Path;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RegionFile.class)
public interface AccessorRegionFile {
    @Accessor("path") Path rdi$getPath();
    @Accessor("timestamps") IntBuffer rdi$getTimestamps();
    @Accessor("offsets") IntBuffer rdi$getOffsets();
}
