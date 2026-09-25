package calebxzau.rdi.mc.client.mixin;

import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.Optional;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionStorage.class)
public interface AccessorSectionStorage {
    @Accessor("simpleRegionStorage")
    SimpleRegionStorage rdi$getSimpleRegionStorage();

    @Accessor("storage")
    Long2ObjectMap<Optional<PoiSection>> rdi$getStorage();
}
