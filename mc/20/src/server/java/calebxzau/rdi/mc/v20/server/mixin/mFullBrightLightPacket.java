package calebxzau.rdi.mc.v20.server.mixin;

import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.BitSet;
import java.util.List;

/**
 * RDI clients compute light locally and discard server light arrays, so light data is sent with no layers.
 * This skips copying every light section on the server and decoding them on the client.
 */
@Mixin(ClientboundLightUpdatePacketData.class)
public class mFullBrightLightPacket {
    @Redirect(
            method = "<init>(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/game/ClientboundLightUpdatePacketData;prepareSectionData(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/lighting/LevelLightEngine;Lnet/minecraft/world/level/LightLayer;ILjava/util/BitSet;Ljava/util/BitSet;Ljava/util/List;)V"
            ),
            require = 2
    )
    private void rdi$omitLightSection(
            ClientboundLightUpdatePacketData data,
            ChunkPos chunkPos,
            LevelLightEngine lightEngine,
            LightLayer lightLayer,
            int index,
            BitSet mask,
            BitSet emptyMask,
            List<byte[]> updates
    ) {
        // Leave both masks and update lists empty.
    }
}
