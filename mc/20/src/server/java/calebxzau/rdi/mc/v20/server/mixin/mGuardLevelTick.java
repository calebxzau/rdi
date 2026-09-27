package calebxzau.rdi.mc.v20.server.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.BiConsumer;

@Mixin(LevelTicks.class)
abstract class mGuardLevelTick {
    @Shadow
    protected abstract void cleanupAfterTick();

    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/ticks/LevelTicks;collectTicks(JILnet/minecraft/util/profiling/ProfilerFiller;)V")
    )
    private void rdi$guardCollectTicks(
            LevelTicks instance,
            long gameTime,
            int maxAllowedTicks,
            ProfilerFiller profiler,
            Operation<Void> original
    ) {
        try {
            original.call(instance, gameTime, maxAllowedTicks, profiler);
        } catch (Exception error) {
            error.printStackTrace();
            cleanupAfterTick();
        }
    }

    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/ticks/LevelTicks;runCollectedTicks(Ljava/util/function/BiConsumer;)V")
    )
    private void rdi$guardRunCollectedTicks(
            LevelTicks instance,
            BiConsumer<?, ?> ticker,
            Operation<Void> original
    ) {
        try {
            original.call(instance, ticker);
        } catch (Exception error) {
            error.printStackTrace();
            cleanupAfterTick();
        }
    }
}
