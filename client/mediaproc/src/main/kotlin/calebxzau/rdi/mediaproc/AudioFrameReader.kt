package calebxzau.rdi.mediaproc

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import java.io.File

/**
 * Decodes the first audio stream of an input frame by frame. Not thread-safe.
 *
 * Damaged audio is tolerated: a packet that fails to decode is skipped, and a read error ends the
 * stream with the frames decoded so far. A failure of the [AvioInput] itself is still reported.
 */
internal class AudioFrameReader private constructor(
    private val formatContext: AVFormatContext,
    private val decoder: AVCodecContext,
    private val streamIndex: Int,
    private val input: AvioInput?,
) : AutoCloseable {
    private val packet: AVPacket = av_packet_alloc() ?: error("Unable to allocate audio packet")
    private val frame: AVFrame = av_frame_alloc() ?: error("Unable to allocate audio frame")
    private var flushing = false
    private var closed = false

    val sampleRate: Int get() = decoder.sample_rate()
    val channels: Int get() = decoder.ch_layout().nb_channels()

    /** The next decoded frame, or null at the end. The frame is only valid until the next call. */
    fun readFrame(): AVFrame? {
        while (true) {
            val result = avcodec_receive_frame(decoder, frame)
            if (result >= 0) return frame
            if (result == AVERROR_EOF() || flushing) return null
            if (!sendNextPacket()) {
                avcodec_send_packet(decoder, null)
                flushing = true
            }
        }
    }

    private fun sendNextPacket(): Boolean {
        while (true) {
            if (av_read_frame(formatContext, packet) < 0) {
                input?.throwCallbackFailureIfAny()
                return false
            }
            try {
                if (packet.stream_index() == streamIndex) {
                    avcodec_send_packet(decoder, packet)
                    return true
                }
            } finally {
                av_packet_unref(packet)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            av_packet_free(packet)
            av_frame_free(frame)
            avcodec_free_context(decoder)
            avformat_close_input(formatContext)
        } finally {
            input?.close()
        }
    }

    companion object {
        private const val AVFMT_FLAG_CUSTOM_IO = 0x0080

        fun open(file: File): AudioFrameReader =
            BytePointer(file.absolutePath, Charsets.UTF_8).use { url -> open(url, null) }

        /** Takes ownership of [input], and closes it if this fails. */
        fun open(input: AvioInput): AudioFrameReader = open(null, input)

        private fun open(url: BytePointer?, input: AvioInput?): AudioFrameReader {
            var formatContext: AVFormatContext? = null
            var opened = false
            var decoder: AVCodecContext? = null
            try {
                val context = avformat_alloc_context() ?: error("Unable to allocate audio input context")
                formatContext = context
                if (input != null) {
                    context.pb(input.context())
                    // AVFMT_FLAG_CUSTOM_IO: avformat_close_input must leave our AVIO context alive.
                    context.flags(context.flags() or AVFMT_FLAG_CUSTOM_IO)
                }
                checkInput(avformat_open_input(context, url, null, null as AVDictionary?), "open audio input", input)
                opened = true
                checkInput(avformat_find_stream_info(context, null as AVDictionary?), "read audio stream information", input)

                val stream = (0 until context.nb_streams())
                    .map { context.streams(it) }
                    .firstOrNull { it.codecpar().codec_type() == AVMEDIA_TYPE_AUDIO }
                    ?: error("Input has no audio stream")
                val parameters = stream.codecpar()
                val codec = avcodec_find_decoder(parameters.codec_id())
                    ?: error("No decoder for audio codec ${parameters.codec_id()}")
                val activeDecoder = avcodec_alloc_context3(codec) ?: error("Unable to allocate audio decoder")
                decoder = activeDecoder
                FfmpegAvifNative.check(avcodec_parameters_to_context(activeDecoder, parameters), "copy audio parameters")
                FfmpegAvifNative.check(
                    avcodec_open2(activeDecoder, codec, null as PointerPointer<AVDictionary>?),
                    "open audio decoder",
                )
                return AudioFrameReader(context, activeDecoder, stream.index(), input)
            } catch (error: Throwable) {
                runCatching { decoder?.let { avcodec_free_context(it) } }
                if (opened) {
                    runCatching { avformat_close_input(formatContext) }
                } else if (formatContext != null && !formatContext.isNull) {
                    // A failed avformat_open_input has already freed it and cleared the pointer.
                    runCatching { avformat_free_context(formatContext) }
                }
                runCatching { input?.close() }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }

        private fun checkInput(code: Int, operation: String, input: AvioInput?) {
            input?.throwCallbackFailureIfAny()
            FfmpegAvifNative.check(code, operation)
        }
    }
}
