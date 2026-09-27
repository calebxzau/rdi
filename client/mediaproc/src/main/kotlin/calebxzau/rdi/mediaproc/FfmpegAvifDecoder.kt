package calebxzau.rdi.mediaproc

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AV1
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_GRAY8
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_clone
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.global.avutil.av_pix_fmt_desc_get
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.PointerPointer
import java.lang.ref.Reference

object FfmpegAvifDecoder {
    private const val MAX_INPUT_BYTES = 64L * 1024L * 1024L
    private const val MAX_DIMENSION = 16_384
    private const val MAX_PIXELS = 64L * 1024L * 1024L
    private const val MAX_STREAMS = 2
    private const val THREAD_LIMIT = 4
    private const val RGBA_CHANNELS = 4
    private const val AVFMT_FLAG_CUSTOM_IO = 0x0080
    private val decodePermits = java.util.concurrent.Semaphore(2)

    fun isAvif(input: ByteArray): Boolean = AvifCodec.isAvif(input)

    fun decode(input: ByteArray): Result<DecodedRgbaImage> = runCatching {
        require(input.size.toLong() <= MAX_INPUT_BYTES) {
            "AVIF input exceeds ${MAX_INPUT_BYTES / (1024 * 1024)}MiB"
        }
        require(AvifCodec.isAvif(input)) { "Input is not an AVIF image" }
        FfmpegAvifNative.requireDecodeReady()
        decodePermits.acquireUninterruptibly()
        try {
            decodeNative(input)
        } finally {
            decodePermits.release()
        }
    }

    private fun decodeNative(input: ByteArray): DecodedRgbaImage {
        val decoders = mutableListOf<AVCodecContext>()
        val storedFrames = arrayOfNulls<AVFrame>(MAX_STREAMS)
        val formatContext = avformat_alloc_context()
            ?: error("Unable to allocate FFmpeg input context")
        val memoryInput = try {
            MemoryAvioInput(input)
        } catch (failure: Throwable) {
            runCatching { avformat_free_context(formatContext) }
            formatContext.close()
            throw failure
        }
        var opened = false
        try {
            formatContext.pb(memoryInput.context())
            // AVFMT_FLAG_CUSTOM_IO: avformat_close_input must leave our AVIO context alive.
            formatContext.flags(formatContext.flags() or AVFMT_FLAG_CUSTOM_IO)
            FfmpegAvifNative.check(
                avformat_open_input(
                    formatContext,
                    null as BytePointer?,
                    null,
                    null as org.bytedeco.ffmpeg.avutil.AVDictionary?,
                ),
                "open AVIF input",
            )
            opened = true
            memoryInput.throwCallbackFailureIfAny()
            FfmpegAvifNative.check(
                avformat_find_stream_info(
                    formatContext,
                    null as org.bytedeco.ffmpeg.avutil.AVDictionary?,
                ),
                "read AVIF stream information",
            )
            memoryInput.throwCallbackFailureIfAny()

            val streams = (0 until formatContext.nb_streams())
                .map { formatContext.streams(it) }
            require(streams.isNotEmpty() && streams.size <= MAX_STREAMS) {
                "AVIF must contain one or two streams"
            }
            require(streams.all { it.codecpar().codec_type() == AVMEDIA_TYPE_VIDEO }) {
                "AVIF contains a non-video stream"
            }

            val colorStream = streams[0]
            val alphaStream = streams.getOrNull(1)
            validateStreams(colorStream, alphaStream)

            val colorDecoder = createDecoder(colorStream.codecpar()).also(decoders::add)
            val alphaDecoder = alphaStream?.let { createDecoder(it.codecpar()).also(decoders::add) }
            decodePackets(
                formatContext,
                colorStream.index(),
                colorDecoder,
                alphaStream?.index(),
                alphaDecoder,
                alphaStream?.codecpar(),
                storedFrames,
            )
            memoryInput.throwCallbackFailureIfAny()

            val colorFrame = storedFrames[0]
                ?: error("AVIF contains no color frame")
            val alphaFrame = storedFrames[1]
            val color = convertFrame(colorFrame, AV_PIX_FMT_RGBA)
            val alpha = alphaFrame?.let { convertFrame(it, AV_PIX_FMT_GRAY8) }
            if (alphaFrame != null) {
                require(colorFrame.width() == alphaFrame.width() && colorFrame.height() == alphaFrame.height()) {
                    "AVIF color and alpha dimensions differ"
                }
            }
            return mergeRgba(color, alpha, colorFrame.width(), colorFrame.height())
        } finally {
            storedFrames.filterNotNull().forEach { runCatching { av_frame_free(it) } }
            decoders.forEach { runCatching { avcodec_free_context(it) } }
            if (opened) {
                runCatching { avformat_close_input(formatContext) }
            } else if (!formatContext.isNull) {
                runCatching { avformat_free_context(formatContext) }
            }
            try {
                formatContext.close()
            } finally {
                try {
                    memoryInput.close()
                } finally {
                    Reference.reachabilityFence(memoryInput)
                }
            }
        }
    }

    private fun validateStreams(colorStream: AVStream, alphaStream: AVStream?) {
        val colorParameters = colorStream.codecpar()
        require(colorParameters.codec_id() == AV_CODEC_ID_AV1) {
            "AVIF color stream is not AV1"
        }
        validateDimensions(colorParameters.width(), colorParameters.height())
        if (alphaStream != null) {
            val alphaParameters = alphaStream.codecpar()
            require(alphaParameters.codec_id() == AV_CODEC_ID_AV1) {
                "AVIF alpha stream is not AV1"
            }
            require(alphaParameters.width() == colorParameters.width()) {
                "AVIF color and alpha widths differ"
            }
            require(alphaParameters.height() == colorParameters.height()) {
                "AVIF color and alpha heights differ"
            }
            val descriptor = av_pix_fmt_desc_get(alphaParameters.format())
            require(descriptor != null && descriptor.nb_components().toInt() == 1) {
                "AVIF alpha stream is not monochrome"
            }
        }
    }

    private fun validateDimensions(width: Int, height: Int) {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
            "AVIF dimensions are unreasonable: ${width}x$height"
        }
        require(width.toLong() * height.toLong() <= MAX_PIXELS) {
            "AVIF pixel count exceeds processing limit"
        }
    }

    private fun createDecoder(parameters: AVCodecParameters): AVCodecContext {
        val codec = avcodec_find_decoder(parameters.codec_id())
            ?: error("AVIF decoder is unavailable for codec ${parameters.codec_id()}")
        val context = avcodec_alloc_context3(codec)
            ?: error("Unable to allocate AVIF decoder context")
        try {
            FfmpegAvifNative.check(
                avcodec_parameters_to_context(context, parameters),
                "copy AVIF decoder parameters",
            )
            context.thread_count(Runtime.getRuntime().availableProcessors().coerceIn(1, THREAD_LIMIT))
            FfmpegAvifNative.check(
                avcodec_open2(context, codec, null as PointerPointer<org.bytedeco.ffmpeg.avutil.AVDictionary>?),
                "open AVIF decoder",
            )
            return context
        } catch (error: Throwable) {
            runCatching { avcodec_free_context(context) }
            throw error
        }
    }

    private fun decodePackets(
        formatContext: AVFormatContext,
        colorIndex: Int,
        colorDecoder: AVCodecContext,
        alphaIndex: Int?,
        alphaDecoder: AVCodecContext?,
        alphaParameters: AVCodecParameters?,
        storedFrames: Array<AVFrame?>,
    ) {
        val packet = AVPacket()
        var colorOutput: AVFrame? = null
        var alphaOutput: AVFrame? = null
        try {
            val allocatedColorOutput = av_frame_alloc()
                ?: error("Unable to allocate AVIF color output frame")
            colorOutput = allocatedColorOutput
            val allocatedAlphaOutput = alphaDecoder?.let {
                av_frame_alloc() ?: error("Unable to allocate AVIF alpha output frame")
            }
            alphaOutput = allocatedAlphaOutput
            while (true) {
                val result = av_read_frame(formatContext, packet)
                if (result == AVERROR_EOF()) break
                FfmpegAvifNative.check(result, "read AVIF packet")
                try {
                    when {
                        packet.stream_index() == colorIndex -> {
                            FfmpegAvifNative.check(
                                avcodec_send_packet(colorDecoder, packet),
                                "send AVIF color packet",
                            )
                            drainDecoder(colorDecoder, allocatedColorOutput, storedFrames, 0)
                        }
                        alphaIndex != null && packet.stream_index() == alphaIndex -> {
                            val decoder = alphaDecoder ?: error("AVIF contains an unexpected alpha packet")
                            val output = allocatedAlphaOutput
                                ?: error("AVIF contains an unexpected alpha packet")
                            FfmpegAvifNative.check(
                                avcodec_send_packet(decoder, packet),
                                "send AVIF alpha packet",
                            )
                            drainDecoder(decoder, output, storedFrames, 1)
                        }
                        else -> error("AVIF contains an unexpected stream")
                    }
                } finally {
                    av_packet_unref(packet)
                }
            }

            FfmpegAvifNative.check(avcodec_send_packet(colorDecoder, null), "flush AVIF color decoder")
            drainDecoder(colorDecoder, allocatedColorOutput, storedFrames, 0)
            if (alphaDecoder != null && allocatedAlphaOutput != null) {
                FfmpegAvifNative.check(avcodec_send_packet(alphaDecoder, null), "flush AVIF alpha decoder")
                drainDecoder(alphaDecoder, allocatedAlphaOutput, storedFrames, 1)
            }
            require(storedFrames[0] != null) { "AVIF contains no color frame" }
            if (alphaParameters != null) {
                require(storedFrames[1] != null) { "AVIF contains no alpha frame" }
            }
        } finally {
            av_packet_unref(packet)
            packet.close()
            colorOutput?.let { av_frame_free(it) }
            alphaOutput?.let { av_frame_free(it) }
        }
    }

    private fun drainDecoder(
        decoder: AVCodecContext,
        output: AVFrame,
        storedFrames: Array<AVFrame?>,
        slot: Int,
    ) {
        while (true) {
            val result = avcodec_receive_frame(decoder, output)
            if (FfmpegAvifNative.isAgain(result)) break
            if (result == AVERROR_EOF()) break
            FfmpegAvifNative.check(result, "receive AVIF frame")
            require(storedFrames[slot] == null) {
                "Animated AVIF images are not supported"
            }
            val clone = av_frame_clone(output)
                ?: error("Unable to retain decoded AVIF frame")
            storedFrames[slot] = clone
            av_frame_unref(output)
        }
    }

    private fun convertFrame(frame: AVFrame, targetFormat: Int): ByteArray {
        val width = frame.width()
        val height = frame.height()
        validateDimensions(width, height)
        val target = av_frame_alloc() ?: error("Unable to allocate converted AVIF frame")
        var scaler: SwsContext? = null
        try {
            target.format(targetFormat).width(width).height(height)
            FfmpegAvifNative.check(
                org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer(target, 32),
                "allocate converted AVIF frame buffer",
            )
            scaler = sws_getContext(
                width,
                height,
                frame.format(),
                width,
                height,
                targetFormat,
                SWS_BILINEAR,
                null as org.bytedeco.ffmpeg.swscale.SwsFilter?,
                null as org.bytedeco.ffmpeg.swscale.SwsFilter?,
                null as DoublePointer?,
            ) ?: error("Unable to allocate AVIF pixel converter")
            check(
                sws_scale(
                    scaler,
                    frame.data(),
                    frame.linesize(),
                    0,
                    height,
                    target.data(),
                    target.linesize(),
                ) == height,
            ) { "FFmpeg failed to convert AVIF pixels" }
            val channels = if (targetFormat == AV_PIX_FMT_RGBA) RGBA_CHANNELS else 1
            val rowBytes = width * channels
            val result = ByteArray(rowBytes * height)
            repeat(height) { row ->
                target.data(0).position(row.toLong() * target.linesize(0)).get(result, row * rowBytes, rowBytes)
            }
            return result
        } finally {
            scaler?.let { sws_freeContext(it) }
            av_frame_free(target)
        }
    }

    private fun mergeRgba(
        color: ByteArray,
        alpha: ByteArray?,
        width: Int,
        height: Int,
    ): DecodedRgbaImage {
        val pixels = color
        if (alpha == null) {
            for (index in 3 until pixels.size step RGBA_CHANNELS) pixels[index] = 0xFF.toByte()
        } else {
            require(alpha.size == width * height) { "AVIF alpha frame has an invalid size" }
            for (pixel in 0 until width * height) pixels[pixel * RGBA_CHANNELS + 3] = alpha[pixel]
        }
        return DecodedRgbaImage(width, height, pixels)
    }

}
