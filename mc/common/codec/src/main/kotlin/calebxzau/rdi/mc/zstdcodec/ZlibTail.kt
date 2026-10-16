package calebxzau.rdi.mc.zstdcodec

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** One packet whose zlib field was inflated in place; [tailOffset] is where its rawLength VarInt starts. */
internal class ZlibTailPacket(val tailOffset: Int, val unwrapped: ByteArray)

/** Converts packets between the original and unwrapped layouts of [ZlibTailFormat]. */
internal object ZlibTail {
    /**
     * Returns the unwrapped packet, or null when [packet] holds no field that matches the layout exactly:
     * two VarInts, a zlib stream that ends after exactly zlibLength bytes, and exactly rawLength bytes out.
     */
    fun unwrap(packet: ByteArray): ZlibTailPacket? {
        if (packet.size < ZlibTailFormat.MINIMUM_PACKET_BYTES) return null
        val searchEnd = minOf(packet.size, ZlibTailFormat.SEARCH_BYTES)
        for (start in 0 until searchEnd) {
            val raw = readVarInt(packet, start) ?: continue
            val rawLength = raw.value
            if (rawLength !in 1..ZlibTailFormat.MAXIMUM_RAW_BYTES) continue
            val zlib = readVarInt(packet, raw.end) ?: continue
            val zlibLength = zlib.value
            val zlibStart = zlib.end
            if (zlibLength !in 2..ZlibTailFormat.MAXIMUM_ZLIB_BYTES || zlibLength > packet.size - zlibStart) continue
            if (!isZlibHeader(packet, zlibStart)) continue
            val inflated = inflateExactly(packet, zlibStart, zlibLength, rawLength) ?: continue
            val suffixStart = zlibStart + zlibLength
            val suffixBytes = packet.size - suffixStart
            val unwrapped = ByteArray(raw.end + rawLength + suffixBytes)
            System.arraycopy(packet, 0, unwrapped, 0, raw.end)
            System.arraycopy(inflated, 0, unwrapped, raw.end, rawLength)
            System.arraycopy(packet, suffixStart, unwrapped, raw.end + rawLength, suffixBytes)
            return ZlibTailPacket(start, unwrapped)
        }
        return null
    }

    /** Deflates the field of an unwrapped packet again; fails when the packet breaks the layout. */
    fun rewrap(unwrapped: ByteArray, tailOffset: Int): Result<ByteArray> = runCatching {
        require(tailOffset in 0 until unwrapped.size) { "Zlib tail offset ${tailOffset} lies outside ${unwrapped.size} bytes" }
        val raw = requireNotNull(readVarInt(unwrapped, tailOffset)) { "Zlib tail field has no valid raw length" }
        val rawLength = raw.value
        require(rawLength in 1..ZlibTailFormat.MAXIMUM_RAW_BYTES) { "Zlib tail field declares ${rawLength} raw bytes" }
        require(rawLength <= unwrapped.size - raw.end) { "Zlib tail field declares ${rawLength} raw bytes beyond the packet" }
        val deflated = deflate(unwrapped, raw.end, rawLength)
        val suffixStart = raw.end + rawLength
        val output = ByteArrayOutputStream(raw.end + 5 + deflated.size + unwrapped.size - suffixStart)
        output.write(unwrapped, 0, raw.end)
        writeVarInt(output, deflated.size)
        output.write(deflated)
        output.write(unwrapped, suffixStart, unwrapped.size - suffixStart)
        output.toByteArray()
    }

    private class VarInt(val value: Int, val end: Int)

    private fun readVarInt(bytes: ByteArray, start: Int): VarInt? {
        var value = 0
        var index = start
        for (shift in 0 until 35 step 7) {
            if (index >= bytes.size) return null
            val current = bytes[index++].toInt() and 0xFF
            value = value or ((current and 0x7F) shl shift)
            if (current and 0x80 == 0) return VarInt(value, index)
        }
        return null
    }

    private fun writeVarInt(output: ByteArrayOutputStream, value: Int) {
        var current = value
        while (current and -0x80 != 0) {
            output.write((current and 0x7F) or 0x80)
            current = current ushr 7
        }
        output.write(current)
    }

    /** CMF 0x78 (deflate, 32 KiB window), no preset dictionary, and the RFC 1950 header check. */
    private fun isZlibHeader(bytes: ByteArray, start: Int): Boolean {
        val cmf = bytes[start].toInt() and 0xFF
        val flg = bytes[start + 1].toInt() and 0xFF
        return cmf == 0x78 && flg and 0x20 == 0 && (cmf * 256 + flg) % 31 == 0
    }

    private fun inflateExactly(bytes: ByteArray, start: Int, length: Int, rawLength: Int): ByteArray? {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, start, length)
            // One spare byte reveals a stream that inflates to more than rawLength.
            val output = ByteArray(rawLength + 1)
            var produced = 0
            while (!inflater.finished() && produced < output.size) {
                val count = inflater.inflate(output, produced, output.size - produced)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                produced += count
            }
            return if (inflater.finished() && inflater.remaining == 0 && produced == rawLength) output else null
        } catch (_: DataFormatException) {
            // Bytes that only look like a zlib header; the packet leaves unchanged.
            return null
        } finally {
            inflater.end()
        }
    }

    private fun deflate(bytes: ByteArray, start: Int, length: Int): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            deflater.setInput(bytes, start, length)
            deflater.finish()
            val output = ByteArrayOutputStream(length / 2 + 64)
            val buffer = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
