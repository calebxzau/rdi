package calebxzau.rdi.mediaproc

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber.SampleMode
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ShortBuffer
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
            loadNativeLibraries()
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

    private fun loadNativeLibraries() {
        FfmpegNativePreloader.ensureLoaded().getOrThrow()
        FFmpegFrameGrabber.tryLoad()
        FFmpegFrameRecorder.tryLoad()
        av_log_set_level(AV_LOG_ERROR)
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

    private fun transcodeFile(inputFile: File, outputFile: File): FileTranscodeResult {
        var grabber: FFmpegFrameGrabber? = null
        var recorder: FFmpegFrameRecorder? = null
        try {
            val activeGrabber = FFmpegFrameGrabber(inputFile)
            grabber = activeGrabber
            activeGrabber.sampleMode = SampleMode.SHORT
            activeGrabber.start()
            activeGrabber.audioChannels = OUTPUT_CHANNELS
            val inputChannels = activeGrabber.audioChannels
            check(inputChannels > 0) { "OGG has no audio channels" }

            val activeRecorder = FFmpegFrameRecorder(outputFile, OUTPUT_CHANNELS)
            recorder = activeRecorder
            activeRecorder.format = "ogg"
            activeRecorder.audioCodecName = OUTPUT_CODEC_NAME
            activeRecorder.audioBitrate = OUTPUT_BITRATE
            activeRecorder.sampleRate = OUTPUT_SAMPLE_RATE
            activeRecorder.audioChannels = OUTPUT_CHANNELS
            activeRecorder.setAudioOption("vbr", "on")
            activeRecorder.setAudioOption("application", "audio")
            try {
                activeRecorder.start()
            } catch (error: Throwable) {
                throw MediaProcUnavailableException(error)
            }

            var recorded = false
            var decodedDurationMicros = 0L
            while (true) {
                val frame = activeGrabber.grabSamples() ?: break
                val frameDurationMicros = frameDurationMicros(frame, inputChannels, activeGrabber.sampleRate)
                decodedDurationMicros = if (frameDurationMicros > MAX_DURATION_MICROS - decodedDurationMicros) {
                    MAX_DURATION_MICROS + 1L
                } else {
                    decodedDurationMicros + frameDurationMicros
                }
                if (decodedDurationMicros > MAX_DURATION_MICROS) {
                    break
                }
                try {
                    activeRecorder.record(frame)
                } catch (error: Throwable) {
                    throw MediaProcUnavailableException(error)
                }
                recorded = true
            }
            if (decodedDurationMicros > MAX_DURATION_MICROS) {
                try {
                    stopRecorder(activeRecorder)
                } finally {
                    recorder = null
                }
                return FileTranscodeResult.TooLong(decodedDurationMicros)
            }
            check(recorded) { "OGG contains no decodable audio frames" }
            try {
                stopRecorder(activeRecorder)
            } finally {
                recorder = null
            }
            try {
                activeGrabber.stop()
            } finally {
                grabber = null
            }
            check(outputFile.exists() && outputFile.length() > 0L) {
                "FFmpeg produced an empty OGG file"
            }
            return FileTranscodeResult.Encoded
        } finally {
            runCatching { recorder?.release() }
            runCatching { grabber?.release() }
        }
    }

    private fun stopRecorder(recorder: FFmpegFrameRecorder) {
        try {
            recorder.stop()
        } catch (error: Throwable) {
            throw MediaProcUnavailableException(error)
        }
    }

    private fun Throwable.asMediaProcUnavailable(): MediaProcUnavailableException? {
        if (this is MediaProcUnavailableException) return this
        if (this is FFmpegFrameGrabber.Exception) {
            val failureCause = cause
            return (failureCause as? LinkageError)?.let { MediaProcUnavailableException(it) }
        }
        if (this is IOException || this is LinkageError) return MediaProcUnavailableException(this)
        return null
    }

    private fun frameDurationMicros(frame: Frame, inputChannels: Int, inputSampleRate: Int): Long {
        val samples = frame.samples
        check(samples != null && samples.size == 1 && samples[0] is ShortBuffer) {
            "FFmpeg did not produce interleaved 16-bit PCM"
        }
        val sampleCount = (samples[0] as ShortBuffer).remaining().toLong() / inputChannels
        val sampleRate = frame.sampleRate.takeIf { it > 0 } ?: inputSampleRate
        check(sampleRate > 0) { "OGG has no audio sample rate" }
        return (sampleCount * 1_000_000L + sampleRate - 1L) / sampleRate
    }

    private sealed interface FileTranscodeResult {
        data object Encoded : FileTranscodeResult

        data class TooLong(val durationMicros: Long) : FileTranscodeResult
    }
}
