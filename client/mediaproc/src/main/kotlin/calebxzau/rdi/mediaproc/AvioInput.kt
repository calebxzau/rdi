package calebxzau.rdi.mediaproc

import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
import org.bytedeco.ffmpeg.global.avformat.AVIO_SEEKABLE_NORMAL
import org.bytedeco.ffmpeg.global.avformat.avio_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avio_context_free
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVERROR_INVALIDDATA
import org.bytedeco.ffmpeg.global.avutil.av_free
import org.bytedeco.ffmpeg.global.avutil.av_malloc
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Where an [AvioInput] gets its bytes. Called from native code, on the thread that drives FFmpeg. */
internal interface AvioSource {
    /** Copies up to [requested] bytes to [target] and returns the count, or 0 at the end of the input. */
    fun read(target: BytePointer, requested: Int): Int

    /** Returns the new position, the size for `AVSEEK_SIZE`, or a negative value when it cannot seek. */
    fun seek(offset: Long, whence: Int): Long
}

/** Reads a stream once, so that long sounds are not held in memory. */
internal class StreamAvioSource(private val input: InputStream) : AvioSource {
    private val chunk = ByteArray(CHUNK_SIZE)

    override fun read(target: BytePointer, requested: Int): Int {
        val count = input.read(chunk, 0, minOf(requested, CHUNK_SIZE))
        if (count <= 0) return 0
        target.position(0).put(chunk, 0, count)
        return count
    }

    override fun seek(offset: Long, whence: Int): Long = -1L

    private companion object {
        const val CHUNK_SIZE = 32 * 1024
    }
}

/** A custom FFmpeg input. Close it after the format context that uses it has stopped using it. */
internal class AvioInput(private val source: AvioSource, seekable: Boolean) : AutoCloseable {
    private val failure = AtomicReference<Throwable?>(null)
    private val opaque = BytePointer(1L)
    private val key = opaque.address()
    private var ioContext: AVIOContext? = null
    private var closed = false

    init {
        var allocatedBuffer: Pointer? = null
        try {
            activeInputs[key] = this
            val buffer = av_malloc(BUFFER_SIZE.toLong())
                ?: error("Unable to allocate AVIO input buffer")
            allocatedBuffer = buffer
            val context = avio_alloc_context(
                BytePointer(buffer), BUFFER_SIZE, 0, opaque, readCallback, null, seekCallback,
            ) ?: error("Unable to allocate AVIO input context")
            ioContext = context
            allocatedBuffer = null
            context.seekable(if (seekable) AVIO_SEEKABLE_NORMAL else 0)
        } catch (error: Throwable) {
            try {
                allocatedBuffer?.let(::av_free)
            } finally {
                runCatching { close() }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
    }

    fun context(): AVIOContext = checkNotNull(ioContext) { "AVIO input is closed" }

    fun throwCallbackFailureIfAny() {
        failure.get()?.let { throw IllegalStateException("AVIO input callback failed", it) }
    }

    override fun close() {
        if (closed) return
        closed = true
        val context = ioContext
        ioContext = null
        try {
            if (context != null) {
                try {
                    av_free(context.buffer())
                    context.buffer(null as BytePointer?)
                } finally {
                    avio_context_free(context)
                }
            }
        } finally {
            activeInputs.remove(key, this)
            opaque.close()
        }
    }

    private inline fun <T> reportingFailure(failureResult: T, block: () -> T): T = try {
        block()
    } catch (error: Throwable) {
        failure.compareAndSet(null, error)
        failureResult
    }

    private companion object {
        const val BUFFER_SIZE = 32 * 1024
        val activeInputs = ConcurrentHashMap<Long, AvioInput>()

        // Keep one callback pair alive for the process, dispatching by each input's unique opaque pointer.
        // Concurrent decoders must not allocate/free JavaCPP callback trampolines against each other.
        val readCallback = object : Read_packet_Pointer_BytePointer_int() {
            override fun call(opaque: Pointer?, target: BytePointer?, requested: Int): Int {
                val input = opaque?.address()?.let(activeInputs::get) ?: return AVERROR_INVALIDDATA()
                return input.reportingFailure(AVERROR_INVALIDDATA()) {
                    val output = checkNotNull(target) { "FFmpeg supplied a null AVIO read buffer" }
                    val count = input.source.read(output, requested)
                    if (count == 0) AVERROR_EOF() else count
                }
            }
        }
        val seekCallback = object : Seek_Pointer_long_int() {
            override fun call(opaque: Pointer?, offset: Long, whence: Int): Long {
                val input = opaque?.address()?.let(activeInputs::get) ?: return AVERROR_INVALIDDATA().toLong()
                return input.reportingFailure(AVERROR_INVALIDDATA().toLong()) {
                    input.source.seek(offset, whence)
                }
            }
        }
    }
}
