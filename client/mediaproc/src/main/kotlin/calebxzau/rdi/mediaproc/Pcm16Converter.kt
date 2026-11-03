package calebxzau.rdi.mediaproc

import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_uninit
import org.bytedeco.ffmpeg.global.swresample.swr_alloc
import org.bytedeco.ffmpeg.global.swresample.swr_alloc_set_opts2
import org.bytedeco.ffmpeg.global.swresample.swr_convert
import org.bytedeco.ffmpeg.global.swresample.swr_free
import org.bytedeco.ffmpeg.global.swresample.swr_get_out_samples
import org.bytedeco.ffmpeg.global.swresample.swr_init
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Converts decoded frames to interleaved 16-bit PCM in the default layout for [channels]. Not thread-safe. */
internal class Pcm16Converter(
    private val channels: Int,
    private val sampleRate: Int,
) : AutoCloseable {
    private val outputLayout = AVChannelLayout().also { av_channel_layout_default(it, channels) }
    private val frameBytes = channels * Short.SIZE_BYTES
    private var context: SwrContext? = null
    private var inputFormat = 0
    private var inputRate = 0
    private var inputChannels = 0
    private var output = BytePointer(0L)
    private var outputCapacity = 0
    private var closed = false

    /** The output of the last conversion, in the form FFmpeg takes it. Valid until the next call. */
    val planes = PointerPointer<BytePointer>(1L)

    /** Converts [frame] and returns how many samples per channel are now in [planes]. */
    fun convert(frame: AVFrame): Int {
        val active = contextFor(frame)
        val capacity = reserve(active, frame.nb_samples())
        val count = swr_convert(active, planes, capacity, frame.extended_data(), frame.nb_samples())
        FfmpegAvifNative.check(count, "convert audio samples")
        return count
    }

    /** Returns samples the converter still holds. Call once, after the last frame. */
    fun flush(): Int {
        val active = context ?: return 0
        val capacity = reserve(active, 0)
        if (capacity == 0) return 0
        val count = swr_convert(active, planes, capacity, null as PointerPointer<*>?, 0)
        FfmpegAvifNative.check(count, "flush audio samples")
        return count
    }

    /** Copies [count] samples per channel from [planes] to a little-endian buffer, the byte order of every target. */
    fun copyOutput(count: Int): ByteBuffer {
        val bytes = ByteArray(count * frameBytes)
        output.position(0).get(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun contextFor(frame: AVFrame): SwrContext {
        val existing = context
        if (existing != null) {
            // The planes of a frame in another format would be read out of bounds.
            check(
                frame.format() == inputFormat && frame.sample_rate() == inputRate &&
                    frame.ch_layout().nb_channels() == inputChannels
            ) { "Audio format changed in the middle of the stream" }
            return existing
        }
        val created = swr_alloc() ?: error("Unable to allocate audio converter")
        context = created
        FfmpegAvifNative.check(
            swr_alloc_set_opts2(
                created, outputLayout, AV_SAMPLE_FMT_S16, sampleRate,
                frame.ch_layout(), frame.format(), frame.sample_rate(), 0, null,
            ),
            "configure audio converter",
        )
        FfmpegAvifNative.check(swr_init(created), "initialize audio converter")
        inputFormat = frame.format()
        inputRate = frame.sample_rate()
        inputChannels = frame.ch_layout().nb_channels()
        return created
    }

    private fun reserve(active: SwrContext, inputSamples: Int): Int {
        val capacity = swr_get_out_samples(active, inputSamples)
        FfmpegAvifNative.check(capacity, "size audio output")
        if (capacity > outputCapacity) {
            output.close()
            output = BytePointer(Math.multiplyExact(capacity, frameBytes).toLong())
            outputCapacity = capacity
            planes.put(0L, output)
        }
        return capacity
    }

    override fun close() {
        if (closed) return
        closed = true
        context?.let { swr_free(it) }
        output.close()
        planes.close()
        av_channel_layout_uninit(outputLayout)
        outputLayout.close()
    }
}
