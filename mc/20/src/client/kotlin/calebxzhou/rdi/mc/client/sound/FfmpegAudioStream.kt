package calebxzhou.rdi.mc.client.sound

import calebxzau.rdi.mediaproc.FfmpegPcmStream
import net.minecraft.client.sounds.AudioStream
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import javax.sound.sampled.AudioFormat

class FfmpegAudioStream(
    private val stream: FfmpegPcmStream
) : AudioStream {
    private val format = AudioFormat(
        stream.format.sampleRate.toFloat(),
        16,
        stream.format.channels,
        true,
        false
    )

    override fun getFormat(): AudioFormat = format

    override fun read(size: Int): ByteBuffer = stream.read(size).getOrElse(::decodeFailure)

    fun readAll(): ByteBuffer = stream.readAll().getOrElse(::decodeFailure)

    override fun close() {
        stream.closeResult().getOrElse(::decodeFailure)
    }

    private fun <T> decodeFailure(error: Throwable): T {
        LGR.error("FFmpeg音频解码失败", error)
        throw IOException("FFmpeg audio decoding failed", error)
    }

    companion object {
        private val LGR = LoggerFactory.getLogger(FfmpegAudioStream::class.java)
    }
}
