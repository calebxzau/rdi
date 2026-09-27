package calebxzau.rdi.mc.v20.client.mixin;

import calebxzau.rdi.mc.v20.client.GlobalPlayerListState;
import calebxzau.rdi.mc.v20.client.RdiClothesResolver;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.SkinManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(SkinManager.class)
public abstract class mSkinManager {
    @Unique
    private static final ThreadLocal<Boolean> RDI$BYPASS = ThreadLocal.withInitial(() -> false);

    @Unique
    private static final Logger RDI$LOGGER = LoggerFactory.getLogger("RDI SkinManager");

    @Inject(method = "registerSkins", at = @At("HEAD"), cancellable = true)
    private void rdi$fetchAndRegisterSkins(
            GameProfile profile,
            SkinManager.SkinTextureCallback callback,
            boolean requireSecure,
            CallbackInfo ci
    ) {
        if (RDI$BYPASS.get() || !GlobalPlayerListState.hasActiveSession()) {
            return;
        }
        GlobalPlayerListState.Session session = GlobalPlayerListState.session();
        if (session == null) {
            return;
        }
        UUID uuid = profile.getId();
        if (uuid == null) {
            RDI$LOGGER.error("Cannot query RDI clothes for profile without a UUID: {}", profile.getName());
            rdi$fallback(profile, callback, requireSecure);
            ci.cancel();
            return;
        }

        RdiClothesResolver.resolveClothes(session, uuid).whenComplete((clothes, throwable) ->
                Minecraft.getInstance().execute(() -> {
                    if (!GlobalPlayerListState.isCurrent(session)) {
                        return;
                    }
                    if (throwable != null) {
                        RDI$LOGGER.error("Failed to query RDI clothes for {}", profile.getName(), throwable);
                        rdi$fallback(profile, callback, requireSecure);
                        return;
                    }
                    RenderSystem.recordRenderCall(() -> {
                        if (!GlobalPlayerListState.isCurrent(session)) {
                            return;
                        }
                        try {
                            ASkinManager manager = (ASkinManager) (Object) this;
                            manager.rdi$registerTexture(
                                    clothes.getSkin(),
                                    MinecraftProfileTexture.Type.SKIN,
                                    callback
                            );
                            if (clothes.getCape() != null) {
                                manager.rdi$registerTexture(
                                        clothes.getCape(),
                                        MinecraftProfileTexture.Type.CAPE,
                                        callback
                                );
                            }
                        } catch (Exception error) {
                            RDI$LOGGER.error("Failed to register RDI clothes for {}", profile.getName(), error);
                            rdi$fallback(profile, callback, requireSecure);
                        }
                    });
                })
        );
        ci.cancel();
    }

    @Unique
    private void rdi$fallback(
            GameProfile profile,
            SkinManager.SkinTextureCallback callback,
            boolean requireSecure
    ) {
        RDI$BYPASS.set(true);
        try {
            ((SkinManager) (Object) this).registerSkins(profile, callback, requireSecure);
        } finally {
            RDI$BYPASS.set(false);
        }
    }
}
