package calebxzau.rdi.mc.server.mixin;

import calebxzhou.rdi.mc.server.network.IdleEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ClientboundRotateHeadPacket.class)
public abstract class mIdleRotateHeadPacket implements IdleEntityPacket {
    @Shadow @Final private int entityId;

    @Override
    public int rdi$entityId() {
        return entityId;
    }
}
