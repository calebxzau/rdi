package calebxzau.rdi.mediaproc

import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AV1
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avformat.av_guess_format
import org.bytedeco.ffmpeg.global.avutil.av_strerror
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacv.FFmpegFrameGrabber

internal object FfmpegAvifNative {
    private const val ERROR_BUFFER_SIZE = 256L
    private const val EAGAIN = -11

    private val decodeReadiness: Result<Unit> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            FfmpegNativePreloader.ensureLoaded().getOrThrow()
            FFmpegFrameGrabber.tryLoad()
            check(avcodec_find_decoder(AV_CODEC_ID_AV1) != null) {
                "FFmpeg AV1 decoder is unavailable"
            }
        }
    }

    private val encodeReadiness: Result<Unit> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            requireDecodeReady()
            check(avcodec_find_encoder_by_name("libaom-av1") != null) {
                "FFmpeg libaom-av1 encoder is unavailable"
            }
            check(av_guess_format("avif", null, null) != null) {
                "FFmpeg AVIF muxer is unavailable"
            }
        }
    }

    fun ensureDecodeReady(): Result<Unit> = decodeReadiness

    fun requireDecodeReady() {
        decodeReadiness.getOrElse { throw MediaProcUnavailableException(it) }
    }

    fun ensureEncodeReady(): Result<Unit> = encodeReadiness

    fun requireEncodeReady() {
        encodeReadiness.getOrElse { throw MediaProcUnavailableException(it) }
    }

    fun ensureReady(): Result<Unit> = ensureEncodeReady()

    fun requireReady() {
        requireEncodeReady()
    }

    fun check(code: Int, operation: String) {
        if (code < 0) throw IllegalStateException("$operation failed: ${errorText(code)}")
    }

    fun errorText(code: Int): String {
        val buffer = BytePointer(ERROR_BUFFER_SIZE)
        return try {
            val result = av_strerror(code, buffer, ERROR_BUFFER_SIZE)
            if (result < 0) "FFmpeg error $code" else buffer.getString()
        } finally {
            buffer.close()
        }
    }

    fun isAgain(code: Int): Boolean = code == EAGAIN
}
