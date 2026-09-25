package calebxzau.rdi.mc.client.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PersistentEntitySectionManager.class)
public interface AccessorPersistentEntitySectionManager {
    @Accessor("sectionStorage")
    EntitySectionStorage<Entity> rdi$getSectionStorage();

    @Accessor("permanentStorage")
    EntityPersistentStorage<Entity> rdi$getPermanentStorage();

    @Accessor("chunkLoadStatuses")
    Long2ObjectMap<?> rdi$getChunkLoadStatuses();

    @Accessor("chunksToUnload")
    LongSet rdi$getChunksToUnload();
}
