package calebxzau.rdi.mc.server.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecraftServer.class)
public interface AMinecraftServer {
    @Accessor("ticksUntilAutosave")
    int rdi$ticksUntilAutosave();
}
