package calebxzau.rdi.mc.server.mixin;

import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

@Mixin(ClientboundLightUpdatePacketData.class)
public class mFullBrightLightPacket {
    @Unique
    private static final byte[] rdi$fullBrightSection = rdi$createFullBrightSection();

    @Shadow
    @Final
    private BitSet skyYMask;

    @Shadow
    @Final
    private BitSet blockYMask;

    @Shadow
    @Final
    private BitSet emptySkyYMask;

    @Shadow
    @Final
    private BitSet emptyBlockYMask;

    @Shadow
    @Final
    private List<byte[]> skyUpdates;

    @Shadow
    @Final
    private List<byte[]> blockUpdates;

    @Inject(
            method = "<init>(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)V",
            at = @At("RETURN")
    )
    private void rdi$sendFullBrightLight(
            ChunkPos chunkPos,
            LevelLightEngine lightEngine,
            @Nullable BitSet skyLight,
            @Nullable BitSet blockLight,
            CallbackInfo ci
    ) {
        int sectionCount = lightEngine.getLightSectionCount();

        skyYMask.clear();
        skyYMask.set(0, sectionCount);
        blockYMask.clear();
        blockYMask.set(0, sectionCount);
        emptySkyYMask.clear();
        emptyBlockYMask.clear();

        skyUpdates.clear();
        blockUpdates.clear();
        for (int i = 0; i < sectionCount; i++) {
            skyUpdates.add(rdi$fullBrightSection);
            blockUpdates.add(rdi$fullBrightSection);
        }
    }

    @Unique
    private static byte[] rdi$createFullBrightSection() {
        byte[] data = new byte[DataLayer.SIZE];
        Arrays.fill(data, (byte) 0xFF);
        return data;
    }
}
