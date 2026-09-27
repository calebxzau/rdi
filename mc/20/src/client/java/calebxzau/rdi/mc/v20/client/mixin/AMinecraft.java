package calebxzau.rdi.mc.v20.client.mixin;

import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface AMinecraft {
    @Accessor("authenticationService")
    YggdrasilAuthenticationService rdi$getAuthenticationService();
}
