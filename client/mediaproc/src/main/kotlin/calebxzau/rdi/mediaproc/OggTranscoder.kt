package calebxzau.rdi.mediaproc

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

class MediaProcUnavailableException(cause: Throwable) :
    IllegalStateException("Media processing native library is unavailable", cause)

sealed interface OggTranscodeResult {
    data class Encoded(val bytes: ByteArray) : OggTranscodeResult

    data class TooLong(val durationMicros: Long) : OggTranscodeResult
}

object OggTranscoder {
    private const val OUTPUT_CODEC_NAME = "libopus"
    private const val OUTPUT_SAMPLE_RATE = 24_000
    private const val OUTPUT_BITRATE = 48_000
    private const val OUTPUT_CHANNELS = 2
    private const val MAX_DURATION_MICROS = 10_000_000L
    private const val MAX_CONCURRENCY = 8

    private val semaphore = Semaphore(MAX_CONCURRENCY)
    private val readiness: Result<Unit> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            FfmpegNativePreloader.ensureLoaded().getOrThrow()
            av_log_set_level(AV_LOG_ERROR)
            val encoder: AVCodec? = avcodec_find_encoder_by_name(OUTPUT_CODEC_NAME)
            check(encoder != null) { "$OUTPUT_CODEC_NAME encoder is unavailable" }
            val decoder: AVCodec? = avcodec_find_decoder(AV_CODEC_ID_OPUS)
            check(decoder != null) { "Opus decoder is unavailable" }
        }
    }

    fun ensureReady(): Result<Unit> = readiness

    suspend fun transcodeOgg(input: ByteArray, tempDir: Path): Result<OggTranscodeResult> =
        semaphore.withPermit {
            ensureReady().fold(
                onSuccess = { transcodeWithTemporaryFiles(input, tempDir) },
                onFailure = { Result.failure(MediaProcUnavailableException(it)) }
            )
    }

    private fun transcodeWithTemporaryFiles(
        input: ByteArray,
        tempDir: Path
    ): Result<OggTranscodeResult> {
        var inputFile: File? = null
        var outputFile: File? = null
        return try {
            Files.createDirectories(tempDir)
            inputFile = Files.createTempFile(tempDir, "ogg-", ".input.ogg").toFile()
            outputFile = Files.createTempFile(tempDir, "ogg-", ".output.ogg").toFile()
            inputFile.writeBytes(input)
            when (val fileResult = transcodeFile(inputFile, outputFile)) {
                FileTranscodeResult.Encoded -> Result.success(OggTranscodeResult.Encoded(outputFile.readBytes()))
                is FileTranscodeResult.TooLong -> Result.success(
                    OggTranscodeResult.TooLong(fileResult.durationMicros)
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error.asMediaProcUnavailable() ?: error)
        } finally {
            runCatching { inputFile?.delete() }
            runCatching { outputFile?.delete() }
        }
    }

    private fun transcodeFile(inputFile: File, outputFile: File): FileTranscodeResult =
        AudioFrameReader.open(inputFile).use { reader ->
            check(reader.channels > 0) { "OGG has no audio channels" }
            encoding { OggOpusWriter(outputFile, OUTPUT_SAMPLE_RATE, OUTPUT_CHANNELS, OUTPUT_BITRATE) }.use { writer ->
                Pcm16Converter(OUTPUT_CHANNELS, OUTPUT_SAMPLE_RATE).use { converter ->
                    var recorded = false
                    var decodedDurationMicros = 0L
                    while (true) {
                        val frame = reader.readFrame() ?: break
                        val frameDurationMicros = frameDurationMicros(frame, reader.sampleRate)
                        if (frameDurationMicros > MAX_DURATION_MICROS - decodedDurationMicros) {
                            return FileTranscodeResult.TooLong(MAX_DURATION_MICROS + 1L)
                        }
                        decodedDurationMicros += frameDurationMicros
                        val samples = converter.convert(frame)
                        encoding { writer.write(converter.planes, samples) }
                        recorded = true
                    }
                    check(recorded) { "OGG contains no decodable audio frames" }
                    val remaining = converter.flush()
                    encoding {
                        writer.write(converter.planes, remaining)
                        writer.finish()
                    }
                }
            }
            check(outputFile.exists() && outputFile.length() > 0L) {
                "FFmpeg produced an empty OGG file"
            }
            FileTranscodeResult.Encoded
        }

    /** A failure while writing means the encoder is broken, whatever the file being transcoded. */
    private inline fun <T> encoding(block: () -> T): T = try {
        block()
    } catch (error: Throwable) {
        throw MediaProcUnavailableException(error)
    }

    private fun Throwable.asMediaProcUnavailable(): MediaProcUnavailableException? {
        if (this is MediaProcUnavailableException) return this
        if (this is IOException || this is LinkageError) return MediaProcUnavailableException(this)
        return null
    }

    private fun frameDurationMicros(frame: AVFrame, inputSampleRate: Int): Long {
        val sampleRate = frame.sample_rate().takeIf { it > 0 } ?: inputSampleRate
        check(sampleRate > 0) { "OGG has no audio sample rate" }
        return (frame.nb_samples().toLong() * 1_000_000L + sampleRate - 1L) / sampleRate
    }

    private sealed interface FileTranscodeResult {
        data object Encoded : FileTranscodeResult

        data class TooLong(val durationMicros: Long) : FileTranscodeResult
    }
}
