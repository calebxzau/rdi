package calebxzau.rdi.mc.v20.server.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;

@Mixin(Level.class)
abstract class mTickingEntity {
    @WrapOperation(
            method = "guardEntityTick",
            at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V")
    )
    private <T extends Entity> void RDI$GuardEntityTick(
            Consumer<T> entityConsumer,
            Object entityObject,
            Operation<Void> original
    ) {
        try {
            original.call(entityConsumer, entityObject);
        } catch (Throwable error) {
            error.printStackTrace();
            if (entityObject instanceof Entity entity) {
                entity.discard();
            }
        }
    }
}
