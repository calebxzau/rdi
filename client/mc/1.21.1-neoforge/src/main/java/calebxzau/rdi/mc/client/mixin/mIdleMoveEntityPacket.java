package calebxzau.rdi.mc.client.mixin;

import calebxzhou.rdi.mc.server.network.IdleEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ClientboundMoveEntityPacket.class)
public abstract class mIdleMoveEntityPacket implements IdleEntityPacket {
    @Shadow @Final protected int entityId;

    @Override
    public int rdi$entityId() {
        return entityId;
    }
}
