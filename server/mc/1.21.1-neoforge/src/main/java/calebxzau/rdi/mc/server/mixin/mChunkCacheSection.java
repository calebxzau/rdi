package calebxzau.rdi.mc.server.mixin;

import calebxzau.rdi.mc.server.chunkcache.ChunkCacheSectionHashHolder;
import calebxzau.rdi.mc.server.chunkcache.SectionHashCache;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LevelChunkSection.class)
public abstract class mChunkCacheSection implements ChunkCacheSectionHashHolder {
    @Unique private SectionHashCache rdi$hashCache;

    @Override
    public SectionHashCache rdi$hashCache() {
        SectionHashCache cache = rdi$hashCache;
        if (cache == null) {
            cache = new SectionHashCache();
            rdi$hashCache = cache;
        }
        return cache;
    }
}
