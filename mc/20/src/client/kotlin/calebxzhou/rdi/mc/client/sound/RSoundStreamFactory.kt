package calebxzhou.rdi.mc.client.sound

import calebxzau.rdi.mediaproc.FfmpegPcmDecoder
import calebxzau.rdi.mediaproc.OggAudioCodec
import calebxzau.rdi.mediaproc.OggCodecDetector
import com.mojang.blaze3d.audio.OggAudioStream
import net.minecraft.client.sounds.AudioStream
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

object RSoundStreamFactory {
    private val lgr = LoggerFactory.getLogger(RSoundStreamFactory::class.java)

    @JvmStatic
    @Throws(IOException::class)
    fun open(input: InputStream): AudioStream {
        val buffered = input as? BufferedInputStream ?: BufferedInputStream(input)
        val opus = openOpusOrNull(buffered)
        if (opus != null) {
            return opus
        }

        return try {
            OggAudioStream(buffered)
        } catch (error: Throwable) {
            runCatching { buffered.close() }
            throw if (error is IOException) error else IOException("Failed to open OGG audio", error)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun openOpusOrNull(input: BufferedInputStream): AudioStream? {
        val codec = try {
            OggCodecDetector.detect(input).getOrElse { error ->
                throw IOException("Failed to inspect OGG audio", error)
            }
        } catch (error: Throwable) {
            runCatching { input.close() }
            throw if (error is IOException) error else IOException("Failed to inspect OGG audio", error)
        }
        if (codec != OggAudioCodec.OPUS) {
            return null
        }

        lgr.info("RDI Opus audio routed to FFmpeg decoder")
        return FfmpegAudioStream(
            FfmpegPcmDecoder.open(input).getOrElse { error ->
                lgr.error("Opus OGG初始化失败", error)
                throw IOException("Failed to open Opus OGG audio", error)
            }
        )
    }
}
