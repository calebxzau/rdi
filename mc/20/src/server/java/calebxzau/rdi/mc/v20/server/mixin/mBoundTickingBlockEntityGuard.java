package calebxzau.rdi.mc.v20.server.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
abstract class mBoundTickingBlockEntityGuard {
    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/BlockEntityTicker;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V"
            )
    )
    private <T extends BlockEntity> void RDI$TickBlockEntityGuard(
            BlockEntityTicker<T> ticker,
            Level level,
            BlockPos pos,
            BlockState state,
            T blockEntity,
            Operation<Void> original
    ) {
        try {
            original.call(ticker, level, pos, state, blockEntity);
        } catch (Throwable error) {
            error.printStackTrace();
            try {
                if (level != null && pos != null) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
                if (blockEntity != null) {
                    blockEntity.setRemoved();
                }
            } catch (Throwable cleanupError) {
                cleanupError.printStackTrace();
            }
        }
    }
}
