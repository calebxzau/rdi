package calebxzau.rdi.mediaproc

import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import java.io.Closeable
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PcmAudioFormat(
    val sampleRate: Int,
    val channels: Int
)

/** Thread-safe: the sound engine may close a stream while another thread is reading it. */
class FfmpegPcmStream internal constructor(
    private val reader: AudioFrameReader,
    private val converter: Pcm16Converter,
    private val input: InputStream,
    val format: PcmAudioFormat
) : Closeable {
    private var pending = ByteBuffer.allocate(0)
    private var endOfStream = false
    private var closed = false

    @Synchronized
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
            pending = decodeNextChunk()
        }
        output.flip()
        output
    }

    @Synchronized
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

    @Synchronized
    fun closeResult(): Result<Unit> = runCatching {
        if (closed) return@runCatching
        closed = true
        try {
            converter.close()
        } finally {
            try {
                reader.close()
            } finally {
                input.close()
            }
        }
    }

    override fun close() {
        closeResult().getOrThrow()
    }

    private fun decodeNextChunk(): ByteBuffer {
        val frame = reader.readFrame()
        if (frame != null) return converter.copyOutput(converter.convert(frame))
        endOfStream = true
        return converter.copyOutput(converter.flush())
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
            val decoder: AVCodec? = avcodec_find_decoder(AV_CODEC_ID_OPUS)
            check(decoder != null) { "Opus decoder is unavailable" }
        }
    }

    fun ensureOpusReady(): Result<Unit> = readiness

    @JvmStatic
    fun requireOpusReady() {
        readiness.getOrElse { throw MediaProcUnavailableException(it) }
    }

    /** Takes ownership of [input]: it is closed with the stream, or right away if this fails. */
    fun open(input: InputStream): Result<FfmpegPcmStream> {
        var reader: AudioFrameReader? = null
        return runCatching {
            ensureOpusReady().getOrThrow()
            val activeReader = AudioFrameReader.open(AvioInput(StreamAvioSource(input), seekable = false))
            reader = activeReader
            check(activeReader.channels > 0) { "OGG has no audio channels" }
            check(activeReader.sampleRate > 0) { "OGG has no audio sample rate" }
            val format = PcmAudioFormat(
                sampleRate = activeReader.sampleRate,
                channels = if (activeReader.channels == 1) 1 else 2,
            )
            FfmpegPcmStream(activeReader, Pcm16Converter(format.channels, format.sampleRate), input, format)
        }.onFailure {
            runCatching { reader?.close() }
            runCatching { input.close() }
        }
    }
}
