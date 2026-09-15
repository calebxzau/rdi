package calebxzhou.rdi.mc.client.mixin;

import calebxzhou.rdi.mc.client.RMcSessionService;
import calebxzau.rdi.mc.client.preview.ItemPreviewExporter;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * calebxzhou @ 2026-01-01 13:13
 */
@Mixin(Minecraft.class)
public class mGame {
    @Unique
    private static final KeyMapping RDI$RCMD_KEY = new KeyMapping("key.rdi.rcmd", InputConstants.KEY_BACKSLASH, "key.categories.multiplayer");

    @Shadow
    @Nullable
    public Screen screen;

    @Shadow
    @Nullable
    private Overlay overlay;

    @Shadow
    private void openChatScreen(String defaultText) {
    }

    @Overwrite
    public boolean allowsTelemetry() {
        return false;
    }

    @Overwrite
    public boolean allowsMultiplayer() {
        return true;
    }
    @Redirect(method = "<init>",
            at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
                    target = "Lcom/mojang/authlib/yggdrasil/YggdrasilAuthenticationService;createMinecraftSessionService()Lcom/mojang/authlib/minecraft/MinecraftSessionService;"))
    private MinecraftSessionService RDI$SessionService(YggdrasilAuthenticationService instance){
        return new RMcSessionService();
    }
    @Overwrite
    private UserApiService createUserApiService(YggdrasilAuthenticationService authenticationService, GameConfig gameConfig) {
        return UserApiService.OFFLINE;
    }

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void RDI$openRcmdChat(CallbackInfo ci) {
        while (RDI$RCMD_KEY.consumeClick()) {
            if (this.screen == null && this.overlay == null) {
                this.openChatScreen("\\");
            }
        }
    }

    @Inject(method = "onGameLoadFinished", at = @At("TAIL"))
    private void RDI$playStartupSound(CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        SoundEvent sound = SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath("rdi", "mc_start")
        );
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0F, 1.0F));
    }

    @Inject(method = "onResourceLoadFinished", at = @At("TAIL"))
    private void RDI$previewResourcesReady(CallbackInfo ci) {
        ItemPreviewExporter.INSTANCE.onResourceLoadFinished();
    }

    @Inject(
            method = "reloadResourcePacks(ZLnet/minecraft/client/Minecraft$GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD")
    )
    private void RDI$previewReloadStarted(CallbackInfoReturnable<?> cir) {
        ItemPreviewExporter.INSTANCE.onResourceReloadStarted();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void RDI$shutdownPreview(CallbackInfo ci) {
        ItemPreviewExporter.INSTANCE.shutdown();
    }
}
