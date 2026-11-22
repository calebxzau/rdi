package calebxzhou.rdi.mc.client.mixin;

import calebxzhou.rdi.mc.client.compat.ClientEntityFailures;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(ClientLevel.class)
public abstract class mClientEntityTickGuard implements ClientEntityFailures {
    @Shadow @Final private EntityTickList tickingEntities;

    @Shadow
    private void tickPassenger(Entity mount, Entity rider) {
        throw new AssertionError();
    }

    @Unique
    private final Set<UUID> rdi$hiddenEntities = ConcurrentHashMap.newKeySet();

    @Override
    public Set<UUID> rdi$hiddenEntityIds() {
        return rdi$hiddenEntities;
    }

    @Unique
    private boolean rdi$skipTick(Entity entity) {
        return rdi$hiddenEntities.contains(entity.getUUID()) && entity != Minecraft.getInstance().player;
    }

    @Unique
    private void rdi$tickPassengersOfSkippedEntity(Entity mount) {
        for (Entity rider : mount.getPassengers()) {
            tickPassenger(mount, rider);
        }
    }

    // Also cover setup, mount maintenance and mod hooks outside Entity.tick()/rideTick().
    @WrapMethod(method = "tickNonPassenger")
    private void rdi$guardEntityUpdate(Entity entity, Operation<Void> original) {
        try {
            if (rdi$skipTick(entity)) {
                rdi$tickPassengersOfSkippedEntity(entity);
            } else {
                original.call(entity);
            }
        } catch (Throwable error) {
            ClientEntityFailures.record(entity, "entity update", error);
        }
    }

    @WrapMethod(method = "tickPassenger")
    private void rdi$guardPassengerUpdate(Entity mount, Entity passenger, Operation<Void> original) {
        try {
            if (rdi$skipTick(passenger) && !passenger.isRemoved() && passenger.getVehicle() == mount) {
                if (passenger instanceof Player || tickingEntities.contains(passenger)) {
                    rdi$tickPassengersOfSkippedEntity(passenger);
                }
            } else {
                // Vanilla also detaches stale passengers here.
                original.call(mount, passenger);
            }
        } catch (Throwable error) {
            ClientEntityFailures.record(passenger, "passenger update", error);
        }
    }

    @WrapOperation(method = "tickNonPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
    private void rdi$tickEntity(Entity entity, Operation<Void> original) {
        try {
            original.call(entity);
        } catch (Throwable error) {
            ClientEntityFailures.record(entity, "tick", error);
        }
    }

    @WrapOperation(method = "tickPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;rideTick()V"))
    private void rdi$tickPassenger(Entity passenger, Operation<Void> original) {
        try {
            original.call(passenger);
        } catch (Throwable error) {
            ClientEntityFailures.record(passenger, "passenger tick", error);
        }
    }
}
