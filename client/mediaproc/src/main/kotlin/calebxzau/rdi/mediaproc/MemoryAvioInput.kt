package calebxzau.rdi.mediaproc

import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FORCE
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_SIZE
import org.bytedeco.javacpp.BytePointer

/** A seekable [AvioSource] over bytes already in memory. */
internal class MemoryAvioInput(private val bytes: ByteArray) : AvioSource {
    private val cursor = Cursor(bytes.size.toLong())

    override fun read(target: BytePointer, requested: Int): Int {
        val start = cursor.position
        val count = cursor.read(requested)
        if (count > 0) target.position(0).put(bytes, start.toInt(), count)
        return count
    }

    override fun seek(offset: Long, whence: Int): Long = cursor.seek(offset, whence)

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
}
