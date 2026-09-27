package calebxzhou.rdi.mc.client.mixin;

import calebxzau.rdi.mediaproc.FfmpegPcmDecoder;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;

/**
 * calebxzhou @ 2026-01-01 13:13
 */
@Mixin(Minecraft.class)
public class mGame {
    @Unique
    private static final Logger RDI$LOGGER = LoggerFactory.getLogger("rdi-startup-sound");

    @Inject(method = "onGameLoadFinished", at = @At("TAIL"))
    private void RDI$playStartupSound(CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        CompletableFuture.runAsync(FfmpegPcmDecoder::requireOpusReady, Util.backgroundExecutor())
                .whenComplete((ignored, error) -> minecraft.execute(() -> {
                    if (error != null) {
                        RDI$LOGGER.error("启动音效FFmpeg warm-up失败", error);
                        return;
                    }
                    SoundEvent sound = SoundEvent.createVariableRangeEvent(
                            ResourceLocation.fromNamespaceAndPath("rdi", "mc_start")
                    );
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0F, 1.0F));
                }));
    }
}
