package calebxzau.rdi.mc.v20.server.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerLevel.class)
abstract class mGuardServerLevelTick {
    @WrapOperation(
            method = "tickBlock",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V")
    )
    private void rdi$guardBlockTick(
            BlockState blockState,
            ServerLevel serverLevel,
            BlockPos blockPos,
            RandomSource randomSource,
            Operation<Void> original
    ) {
        try {
            original.call(blockState, serverLevel, blockPos, randomSource);
        } catch (Exception error) {
            serverLevel.setBlock(blockPos, Blocks.AIR.defaultBlockState(), 0);
            error.printStackTrace();
        }
    }

    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tickBlockEntities()V")
    )
    private void rdi$guardBlockEntityTicks(ServerLevel level, Operation<Void> original) {
        try {
            original.call(level);
        } catch (Exception error) {
            error.printStackTrace();
        }
    }
}
