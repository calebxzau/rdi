package calebxzau.rdi.mediaproc

import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
import org.bytedeco.ffmpeg.global.avformat.AVIO_SEEKABLE_NORMAL
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FORCE
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_SIZE
import org.bytedeco.ffmpeg.global.avformat.avio_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avio_context_free
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVERROR_INVALIDDATA
import org.bytedeco.ffmpeg.global.avutil.av_free
import org.bytedeco.ffmpeg.global.avutil.av_malloc
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** A seekable in-memory input. Close after its owning format context has stopped using it. */
internal class MemoryAvioInput(bytes: ByteArray) : AutoCloseable {
    private val state = InputState(bytes)
    private val opaque = BytePointer(1L)
    private val key = opaque.address()
    private var ioContext: AVIOContext? = null
    private var closed = false

    init {
        var allocatedBuffer: Pointer? = null
        try {
            activeInputs[key] = state
            val buffer = av_malloc(BUFFER_SIZE.toLong())
                ?: error("Unable to allocate AVIO input buffer")
            allocatedBuffer = buffer
            ioContext = avio_alloc_context(
                BytePointer(buffer), BUFFER_SIZE, 0, opaque, readCallback, null, seekCallback,
            ) ?: error("Unable to allocate AVIO input context")
            allocatedBuffer = null
            ioContext!!.seekable(AVIO_SEEKABLE_NORMAL)
        } catch (failure: Throwable) {
            try {
                allocatedBuffer?.let(::av_free)
            } finally {
                runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    fun context(): AVIOContext = checkNotNull(ioContext) { "AVIO input is closed" }

    fun throwCallbackFailureIfAny() {
        state.failure.get()?.let { throw IllegalStateException("AVIO memory callback failed", it) }
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
            activeInputs.remove(key, state)
            opaque.close()
        }
    }

    private class InputState(val bytes: ByteArray) {
        val cursor = Cursor(bytes.size.toLong())
        val failure = AtomicReference<Throwable?>(null)
    }

    internal class Cursor(private val length: Long) {
        var position: Long = 0
            private set

        fun read(requested: Int): Int {
            require(requested >= 0) { "AVIO requested a negative read size" }
            val count = minOf(requested.toLong(), length - position).toInt()
            position += count
            return count
        }

        fun seek(offset: Long, whence: Int): Long {
            val operation = whence and AVSEEK_FORCE.inv()
            if (operation == AVSEEK_SIZE) return length
            val base = when (operation) {
                0 -> 0L
                1 -> position
                2 -> length
                else -> throw IllegalArgumentException("Unsupported AVIO seek mode: $whence")
            }
            val target = Math.addExact(base, offset)
            require(target in 0..length) { "AVIO seek is outside the input" }
            position = target
            return target
        }
    }

    private companion object {
        const val BUFFER_SIZE = 32 * 1024
        val activeInputs = ConcurrentHashMap<Long, InputState>()

        // Keep one callback pair alive for the process, dispatching by each input's unique opaque pointer.
        // Concurrent decoders must not allocate/free JavaCPP callback trampolines against each other.
        val readCallback = object : Read_packet_Pointer_BytePointer_int() {
            override fun call(opaque: Pointer?, target: BytePointer?, requested: Int): Int {
                val input = opaque?.address()?.let(activeInputs::get) ?: return AVERROR_INVALIDDATA()
                return try {
                    val output = checkNotNull(target) { "FFmpeg supplied a null AVIO read buffer" }
                    val start = input.cursor.position
                    val count = input.cursor.read(requested)
                    if (count == 0) AVERROR_EOF() else {
                        output.position(0).put(input.bytes, start.toInt(), count)
                        count
                    }
                } catch (failure: Throwable) {
                    input.failure.compareAndSet(null, failure)
                    AVERROR_INVALIDDATA()
                }
            }
        }
        val seekCallback = object : Seek_Pointer_long_int() {
            override fun call(opaque: Pointer?, offset: Long, whence: Int): Long {
                val input = opaque?.address()?.let(activeInputs::get) ?: return AVERROR_INVALIDDATA().toLong()
                return try {
                    input.cursor.seek(offset, whence)
                } catch (failure: Throwable) {
                    input.failure.compareAndSet(null, failure)
                    AVERROR_INVALIDDATA().toLong()
                }
            }
        }
    }
}
