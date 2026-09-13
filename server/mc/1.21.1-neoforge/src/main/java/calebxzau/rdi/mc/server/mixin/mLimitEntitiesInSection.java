package calebxzau.rdi.mc.server.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Comparator;
import java.util.stream.Stream;

@Mixin(EntitySection.class)
public abstract class mLimitEntitiesInSection<T extends EntityAccess> {
    @Unique
    private static final int RDI$MAX_ENTITIES_PER_SECTION = 512;

    @Shadow
    public abstract int size();

    @Shadow
    public abstract Stream<T> getEntities();

    @Inject(method = "add", at = @At("TAIL"))
    private void rdi$discardOldestEntityWhenTooMany(T entity, CallbackInfo ci) {
        if (size() <= RDI$MAX_ENTITIES_PER_SECTION) {
            return;
        }
        getEntities()
                .filter(Entity.class::isInstance)
                .map(Entity.class::cast)
                .filter(it -> !(it instanceof Player))
                .filter(it -> !it.isRemoved())
                .min(Comparator.comparingInt(Entity::getId))
                .ifPresent(it -> it.setRemoved(Entity.RemovalReason.DISCARDED));
    }
}
