package calebxzau.rdi.mc.v20.fabric.mixin;

import calebxzhou.rdi.mc.client.sound.FfmpegAudioStream;
import calebxzhou.rdi.mc.client.sound.RSoundStreamFactory;
import com.mojang.blaze3d.audio.OggAudioStream;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.Util;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.LoopingAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Mixin(SoundBufferLibrary.class)
public abstract class mFabricSoundBufferLibrary {
    @Shadow @Final
    private ResourceProvider resourceManager;

    @Shadow @Final
    private Map<ResourceLocation, CompletableFuture<SoundBuffer>> cache;

    @Inject(method = "getCompleteBuffer", at = @At("HEAD"), cancellable = true)
    private void rdi$loadCompleteBuffer(
            ResourceLocation soundId,
            CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir
    ) {
        cir.setReturnValue(this.cache.computeIfAbsent(soundId, id -> CompletableFuture.supplyAsync(() -> {
            try (AudioStream stream = RSoundStreamFactory.open(this.resourceManager.open(id))) {
                ByteBuffer bytes;
                if (stream instanceof FfmpegAudioStream ffmpegStream) {
                    bytes = ffmpegStream.readAll();
                } else if (stream instanceof OggAudioStream oggStream) {
                    bytes = oggStream.readAll();
                } else {
                    throw new IOException("Unsupported RDI audio stream type: " + stream.getClass().getName());
                }
                return new SoundBuffer(bytes, stream.getFormat());
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, Util.backgroundExecutor())));
    }

    @Inject(method = "getStream", at = @At("HEAD"), cancellable = true)
    private void rdi$loadStream(
            ResourceLocation soundId,
            boolean isWrapper,
            CallbackInfoReturnable<CompletableFuture<AudioStream>> cir
    ) {
        cir.setReturnValue(CompletableFuture.supplyAsync(() -> {
            InputStream input = null;
            try {
                input = this.resourceManager.open(soundId);
                AudioStream stream = isWrapper
                        ? new LoopingAudioStream(RSoundStreamFactory::open, input)
                        : RSoundStreamFactory.open(input);
                input = null;
                return stream;
            } catch (IOException exception) {
                closeAfterFailure(input, exception);
                throw new CompletionException(exception);
            } catch (RuntimeException | Error exception) {
                closeAfterFailure(input, exception);
                throw exception;
            }
        }, Util.backgroundExecutor()));
    }

    @Unique
    private static void closeAfterFailure(InputStream input, Throwable failure) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
