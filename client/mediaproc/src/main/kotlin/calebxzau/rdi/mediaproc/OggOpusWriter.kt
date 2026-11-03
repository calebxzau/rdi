package calebxzau.rdi.mediaproc

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVAudioFifo
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_GLOBAL_HEADER
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_from_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avformat.AVFMT_GLOBALHEADER
import org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE
import org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame
import org.bytedeco.ffmpeg.global.avformat.av_write_trailer
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_new_stream
import org.bytedeco.ffmpeg.global.avformat.avformat_write_header
import org.bytedeco.ffmpeg.global.avformat.avio_closep
import org.bytedeco.ffmpeg.global.avformat.avio_open
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_audio_fifo_alloc
import org.bytedeco.ffmpeg.global.avutil.av_audio_fifo_free
import org.bytedeco.ffmpeg.global.avutil.av_audio_fifo_read
import org.bytedeco.ffmpeg.global.avutil.av_audio_fifo_size
import org.bytedeco.ffmpeg.global.avutil.av_audio_fifo_write
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_uninit
import org.bytedeco.ffmpeg.global.avutil.av_dict_count
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable
import org.bytedeco.ffmpeg.global.avutil.av_make_q
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import java.io.File

/**
 * Encodes interleaved 16-bit PCM to an Ogg Opus file. Not thread-safe.
 *
 * libopus only takes frames of an exact length, so input of any length is held in a queue until a
 * whole frame is ready. Only the last frame of the file may be shorter.
 */
internal class OggOpusWriter(
    outputFile: File,
    sampleRate: Int,
    channels: Int,
    bitrate: Int,
) : AutoCloseable {
    private val layout = AVChannelLayout().also { av_channel_layout_default(it, channels) }
    private val formatContext = AVFormatContext(null as Pointer?)
    private var ioContext: AVIOContext? = null
    private var encoder: AVCodecContext? = null
    private var stream: AVStream? = null
    private var queue: AVAudioFifo? = null
    private var frame: AVFrame? = null
    private var packet: AVPacket? = null
    private var frameSamples = 0
    private var nextPts = 0L
    private var closed = false

    init {
        try {
            open(outputFile, sampleRate, channels, bitrate)
        } catch (error: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun open(outputFile: File, sampleRate: Int, channels: Int, bitrate: Int) {
        FfmpegAvifNative.check(
            avformat_alloc_output_context2(formatContext, null, "ogg", null as String?),
            "allocate Ogg output",
        )
        val codec = avcodec_find_encoder_by_name(ENCODER_NAME) ?: error("${ENCODER_NAME} encoder is unavailable")
        val activeStream = avformat_new_stream(formatContext, null) ?: error("Unable to allocate Ogg stream")
        stream = activeStream
        val activeEncoder = avcodec_alloc_context3(codec) ?: error("Unable to allocate Opus encoder")
        encoder = activeEncoder

        val timeBase = av_make_q(1, sampleRate)
        activeEncoder.bit_rate(bitrate.toLong())
        activeEncoder.sample_rate(sampleRate)
        activeEncoder.ch_layout(layout)
        activeEncoder.sample_fmt(AV_SAMPLE_FMT_S16)
        activeEncoder.time_base(timeBase)
        activeStream.time_base(timeBase)
        if (formatContext.oformat().flags() and AVFMT_GLOBALHEADER != 0) {
            activeEncoder.flags(activeEncoder.flags() or AV_CODEC_FLAG_GLOBAL_HEADER)
        }
        openEncoder(activeEncoder, codec)
        FfmpegAvifNative.check(
            avcodec_parameters_from_context(activeStream.codecpar(), activeEncoder),
            "copy Opus parameters",
        )

        frameSamples = activeEncoder.frame_size()
        check(frameSamples > 0) { "Opus encoder has no frame size" }
        queue = av_audio_fifo_alloc(AV_SAMPLE_FMT_S16, channels, frameSamples * 2)
            ?: error("Unable to allocate audio queue")
        packet = av_packet_alloc() ?: error("Unable to allocate Opus packet")
        val activeFrame = av_frame_alloc() ?: error("Unable to allocate Opus frame")
        frame = activeFrame
        activeFrame.format(AV_SAMPLE_FMT_S16)
        activeFrame.sample_rate(sampleRate)
        activeFrame.ch_layout(activeEncoder.ch_layout())
        activeFrame.nb_samples(frameSamples)
        FfmpegAvifNative.check(av_frame_get_buffer(activeFrame, 0), "allocate Opus frame buffer")

        // The file protocol takes UTF-8 paths on every platform; the temporary directory may not be ASCII.
        val activeIo = AVIOContext(null as Pointer?)
        BytePointer(outputFile.absolutePath, Charsets.UTF_8).use { url ->
            FfmpegAvifNative.check(avio_open(activeIo, url, AVIO_FLAG_WRITE), "open Ogg output file")
        }
        ioContext = activeIo
        formatContext.pb(activeIo)
        FfmpegAvifNative.check(avformat_write_header(formatContext, null as AVDictionary?), "write Ogg header")
    }

    private fun openEncoder(activeEncoder: AVCodecContext, codec: org.bytedeco.ffmpeg.avcodec.AVCodec) {
        val options = AVDictionary(null as Pointer?)
        try {
            ENCODER_OPTIONS.forEach { (key, value) ->
                FfmpegAvifNative.check(av_dict_set(options, key, value, 0), "set Opus option ${key}")
            }
            FfmpegAvifNative.check(avcodec_open2(activeEncoder, codec, options), "open Opus encoder")
            check(av_dict_count(options) == 0) { "Opus encoder rejected some options" }
        } finally {
            av_dict_free(options)
        }
    }

    /** Queues [samples] per channel from [planes], and encodes every whole frame that is now ready. */
    fun write(planes: PointerPointer<*>, samples: Int) {
        if (samples == 0) return
        val activeQueue = checkNotNull(queue)
        check(av_audio_fifo_write(activeQueue, planes, samples) == samples) { "Unable to queue audio samples" }
        while (av_audio_fifo_size(activeQueue) >= frameSamples) encodeQueued(frameSamples)
    }

    /** Encodes what is left and completes the file. Without this call the file is not valid. */
    fun finish() {
        val remaining = av_audio_fifo_size(checkNotNull(queue))
        if (remaining > 0) encodeQueued(remaining)
        sendToEncoder(null)
        FfmpegAvifNative.check(av_write_trailer(formatContext), "write Ogg trailer")
    }

    private fun encodeQueued(samples: Int) {
        val activeFrame = checkNotNull(frame)
        FfmpegAvifNative.check(av_frame_make_writable(activeFrame), "prepare Opus frame")
        activeFrame.nb_samples(samples)
        check(av_audio_fifo_read(checkNotNull(queue), activeFrame.data(), samples) == samples) {
            "Unable to read queued audio samples"
        }
        activeFrame.pts(nextPts)
        nextPts += samples
        sendToEncoder(activeFrame)
    }

    private fun sendToEncoder(input: AVFrame?) {
        val activeEncoder = checkNotNull(encoder)
        val activeStream = checkNotNull(stream)
        val activePacket = checkNotNull(packet)
        FfmpegAvifNative.check(avcodec_send_frame(activeEncoder, input), "send audio to Opus encoder")
        while (true) {
            val result = avcodec_receive_packet(activeEncoder, activePacket)
            if (FfmpegAvifNative.isAgain(result) || result == AVERROR_EOF()) return
            FfmpegAvifNative.check(result, "receive Opus packet")
            activePacket.stream_index(activeStream.index())
            // The muxer may have changed the stream's time base while writing the header.
            av_packet_rescale_ts(activePacket, activeEncoder.time_base(), activeStream.time_base())
            FfmpegAvifNative.check(av_interleaved_write_frame(formatContext, activePacket), "write Opus packet")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            packet?.let { av_packet_free(it) }
            frame?.let { av_frame_free(it) }
            queue?.let { av_audio_fifo_free(it) }
            encoder?.let { avcodec_free_context(it) }
            ioContext?.let { io ->
                // avio_closep clears this pointer, so the format context must not keep its own copy.
                formatContext.pb(null as AVIOContext?)
                avio_closep(io)
            }
        } finally {
            if (!formatContext.isNull) avformat_free_context(formatContext)
            av_channel_layout_uninit(layout)
            layout.close()
        }
    }

    private companion object {
        const val ENCODER_NAME = "libopus"
        val ENCODER_OPTIONS = mapOf("vbr" to "on", "application" to "audio")
    }
}
