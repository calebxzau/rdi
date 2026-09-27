package calebxzhou.rdi.mc.client.mixin;

import calebxzhou.rdi.mc.common.RDI;
import com.mojang.util.UUIDTypeAdapter;
import net.minecraft.client.User;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.UUID;

/**
 * calebxzhou @ 2026-02-20 20:10
 */
@Mixin(User.class)
public class mUser {
    @Shadow
    @Final
    @Mutable
    private String name;

    @Mutable
    @Shadow
    @Final
    private String uuid;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void RDI$InjectUser(String name, String uuid, String accessToken, Optional xuid, Optional clientId, User.Type type, CallbackInfo ci) {
        if (RDI.PLAYER_NAME != null) {
            this.name = RDI.PLAYER_NAME;
        }
        if (RDI.PLAYER_ID != null) {
            this.uuid = UUIDTypeAdapter.fromUUID(RDI.PLAYER_ID);
        }
        LoggerFactory.getLogger("rdi-user").info("已登录RDI用户：{}  {} ", RDI.PLAYER_NAME, RDI.PLAYER_ID);
    }
}
