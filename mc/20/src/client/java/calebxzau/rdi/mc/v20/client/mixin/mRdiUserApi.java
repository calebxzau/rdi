package calebxzau.rdi.mc.v20.client.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(Minecraft.class)
public abstract class mRdiUserApi {
    /**
     * RDI supplies its own account token, which must not be sent to Mojang's account API.
     *
     * @author calebxzhou
     * @reason Use the established RDI offline account policy on both 1.20.1 loaders.
     */
    @Overwrite
    private UserApiService createUserApiService(
            YggdrasilAuthenticationService authenticationService,
            GameConfig gameConfig
    ) {
        return UserApiService.OFFLINE;
    }
}
