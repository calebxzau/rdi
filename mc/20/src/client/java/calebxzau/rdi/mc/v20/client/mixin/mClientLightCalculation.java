package calebxzau.rdi.mc.v20.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.BitSet;
import java.util.Iterator;

@Mixin(ClientPacketListener.class)
public abstract class mClientLightCalculation {
    @Shadow
    private ClientLevel level;

    @Redirect(
            method = "applyLightData",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;readSectionList(IILnet/minecraft/world/level/lighting/LevelLightEngine;Lnet/minecraft/world/level/LightLayer;Ljava/util/BitSet;Ljava/util/BitSet;Ljava/util/Iterator;)V"
            ),
            require = 2
    )
    private void rdi$ignoreServerLightData(
            ClientPacketListener listener,
            int x,
            int z,
            LevelLightEngine lightEngine,
            LightLayer lightLayer,
            BitSet mask,
            BitSet emptyMask,
            Iterator<byte[]> updates
    ) {
        // Discard server light arrays while allowing applyLightData to finish normally.
    }

    @Inject(method = "enableChunkLight", at = @At("TAIL"))
    private void rdi$propagateLightSources(LevelChunk chunk, int x, int z, CallbackInfo ci) {
        this.level.getChunkSource().getLightEngine().propagateLightSources(chunk.getPos());
    }
}
