package calebxzhou.rdi.mc.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Optional;

@Mixin(ItemStack.class)
public abstract class mItemTooltipGuard {
    @SuppressWarnings("removal")
    @WrapMethod(method = "getTooltipImage()Ljava/util/Optional;")
    private Optional<TooltipComponent> rdi$skipTooltipImageFailure(Operation<Optional<TooltipComponent>> original) {
        try {
            return original.call();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Keep the text tooltip usable when an item's preview callback fails.
            return Optional.empty();
        }
    }
}
