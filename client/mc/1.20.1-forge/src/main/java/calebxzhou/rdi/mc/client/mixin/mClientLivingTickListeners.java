package calebxzhou.rdi.mc.client.mixin;

import calebxzhou.rdi.mc.client.compat.ClientEntityFailures;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ForgeHooks.class, remap = false)
public abstract class mClientLivingTickListeners {
    @WrapOperation(method = "onLivingTick", at = @At(value = "INVOKE", target = "Lnet/minecraftforge/eventbus/api/IEventBus;post(Lnet/minecraftforge/eventbus/api/Event;)Z"))
    private static boolean rdi$postLivingTick(IEventBus bus, Event event, Operation<Boolean> original) {
        if (!(event instanceof LivingTickEvent tick) || !(tick.getEntity().level() instanceof ClientEntityFailures)) {
            return original.call(bus, event);
        }
        // Catch the failing listener before Forge logs the entire listener list and aborts the player's tick.
        return bus.post(event, (listener, posted) -> {
            try {
                listener.invoke(posted);
            } catch (Throwable error) {
                ClientEntityFailures.record(((LivingTickEvent) posted).getEntity(), "living tick listener", error);
            }
        });
    }
}
