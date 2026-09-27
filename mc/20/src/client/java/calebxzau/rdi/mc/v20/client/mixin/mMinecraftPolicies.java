package calebxzau.rdi.mc.v20.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(Minecraft.class)
public abstract class mMinecraftPolicies {
    /**
     * @author RDI
     * @reason RDI does not submit optional Minecraft telemetry.
     */
    @Overwrite
    public boolean allowsTelemetry() {
        return false;
    }

    /**
     * @author RDI
     * @reason RDI room access must remain available for launcher-provided offline accounts.
     */
    @Overwrite
    public boolean allowsMultiplayer() {
        return true;
    }
}
