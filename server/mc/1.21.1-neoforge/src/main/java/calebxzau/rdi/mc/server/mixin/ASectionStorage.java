package calebxzau.rdi.mc.server.mixin;

import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionStorage.class)
public interface ASectionStorage {
    @Accessor("simpleRegionStorage")
    SimpleRegionStorage rdi$simpleRegionStorage();
}
