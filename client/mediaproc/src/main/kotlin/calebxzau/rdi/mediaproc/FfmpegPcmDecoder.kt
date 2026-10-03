package calebxzau.rdi.mediaproc

import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber.SampleMode
import java.io.Closeable
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

data class PcmAudioFormat(
    val sampleRate: Int,
    val channels: Int
)

class FfmpegPcmStream internal constructor(
    private val grabber: FFmpegFrameGrabber,
    private val input: InputStream,
    val format: PcmAudioFormat
) : Closeable {
    private var pending = ByteBuffer.allocate(0)
    private var endOfStream = false
    private var closed = false

    fun read(maxBytes: Int): Result<ByteBuffer> = runCatching {
        check(!closed) { "PCM stream is closed" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        val frameSize = format.channels * Short.SIZE_BYTES
        val outputSize = maxBytes - maxBytes % frameSize
        require(outputSize > 0) { "maxBytes must contain at least one PCM frame" }
        val output = ByteBuffer.allocateDirect(outputSize).order(ByteOrder.LITTLE_ENDIAN)

        while (output.hasRemaining()) {
            if (pending.hasRemaining()) {
                copyBytes(pending, output)
                continue
            }
            if (endOfStream) break
            pending = grabNextFrame()
        }
        output.flip()
        output
    }

    fun readAll(maxBytes: Int = FfmpegPcmDecoder.DEFAULT_MAX_COMPLETE_PCM_BYTES): Result<ByteBuffer> = runCatching {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val frameSize = format.channels * Short.SIZE_BYTES
        val boundedSize = maxBytes - maxBytes % frameSize
        require(boundedSize > 0) { "maxBytes must contain at least one PCM frame" }

        val chunks = ArrayList<ByteBuffer>()
        var totalBytes = 0
        while (true) {
            val remaining = boundedSize - totalBytes
            if (remaining == 0) {
                val extra = read(frameSize).getOrThrow()
                if (extra.hasRemaining()) {
                    throw IllegalArgumentException("Decoded PCM exceeds $maxBytes bytes")
                }
                break
            }
            val chunk = read(minOf(READ_CHUNK_BYTES, remaining)).getOrThrow()
            if (!chunk.hasRemaining()) break
            totalBytes += chunk.remaining()
            chunks += chunk
        }

        ByteBuffer.allocateDirect(totalBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                chunks.forEach { put(it.duplicate()) }
                flip()
            }
    }

    fun closeResult(): Result<Unit> = runCatching {
        if (closed) return@runCatching
        closed = true
        try {
            grabber.release()
        } finally {
            input.close()
        }
    }

    override fun close() {
        closeResult().getOrThrow()
    }

    private fun grabNextFrame(): ByteBuffer {
        val frame = grabber.grabSamples()
        if (frame == null) {
            endOfStream = true
            return ByteBuffer.allocate(0)
        }
        val samples = frame.samples
        check(samples.size == 1 && samples[0] is ShortBuffer) {
            "FFmpeg did not produce interleaved 16-bit PCM"
        }
        val shorts = (samples[0] as ShortBuffer).duplicate()
        return ByteBuffer.allocateDirect(shorts.remaining() * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                while (shorts.hasRemaining()) putShort(shorts.get())
                flip()
            }
    }

    private fun copyBytes(source: ByteBuffer, target: ByteBuffer) {
        val originalLimit = source.limit()
        source.limit(minOf(source.position() + target.remaining(), originalLimit))
        target.put(source)
        source.limit(originalLimit)
    }

    private companion object {
        const val READ_CHUNK_BYTES = 64 * 1024
    }
}

object FfmpegPcmDecoder {
    const val DEFAULT_MAX_COMPLETE_PCM_BYTES = 32 * 1024 * 1024

    private val readiness: Result<Unit> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            FfmpegNativePreloader.ensureLoaded().getOrThrow()
            FFmpegFrameGrabber.tryLoad()
            val decoder: AVCodec? = avcodec_find_decoder(AV_CODEC_ID_OPUS)
            check(decoder != null) { "Opus decoder is unavailable" }
        }
    }

    fun ensureOpusReady(): Result<Unit> = readiness

    @JvmStatic
    fun requireOpusReady() {
        readiness.getOrElse { throw MediaProcUnavailableException(it) }
    }

    fun open(input: InputStream): Result<FfmpegPcmStream> {
        var grabber: FFmpegFrameGrabber? = null
        return runCatching {
            ensureOpusReady().getOrThrow()
            val activeGrabber = FFmpegFrameGrabber(input, 0)
            grabber = activeGrabber
            activeGrabber.setCloseInputStream(false)
            activeGrabber.sampleMode = SampleMode.SHORT
            activeGrabber.start()
            check(activeGrabber.hasAudio()) { "OGG has no audio stream" }
            val outputChannels = if (activeGrabber.audioChannels == 1) 1 else 2
            activeGrabber.audioChannels = outputChannels
            FfmpegPcmStream(
                activeGrabber,
                input,
                PcmAudioFormat(
                    sampleRate = activeGrabber.sampleRate,
                    channels = outputChannels
                )
            )
        }.onFailure {
            runCatching { grabber?.release() }
            runCatching { input.close() }
        }
    }
}
