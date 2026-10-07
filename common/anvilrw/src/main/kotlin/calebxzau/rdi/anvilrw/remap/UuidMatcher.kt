package calebxzau.rdi.anvilrw.remap

import java.util.UUID

/**
 * Matches whole source UUIDs and writes their mapped targets.
 *
 * Only full 128-bit values are ever matched: 16 binary bytes, or a complete 36-character dashed or
 * 32-character dashless hex string with hex boundaries on both sides. Halves, single longs and hex
 * prefixes never match. Every replacement has the same length as the text or bytes it replaces.
 *
 * The mapping must be injective. A target may also be a source only as part of a swap (`a → b` and
 * `b → a`), which keeps two profiles of one person apart; any longer chain is rejected. Matches are
 * always read from the unpatched input, so a swap is applied in one simultaneous step.
 */
internal class UuidMatcher(mapping: Map<UUID, UUID>) {
    val sources: List<UUID> = mapping.keys.toList()
    private val sourceMsb = LongArray(sources.size) { sources[it].mostSignificantBits }
    private val sourceLsb = LongArray(sources.size) { sources[it].leastSignificantBits }
    private val targetMsb: LongArray
    private val targetLsb: LongArray

    init {
        val targets = sources.map { mapping.getValue(it) }
        require(targets.toSet().size == targets.size) { "Two source UUIDs map to the same target" }
        mapping.forEach { (source, target) ->
            require(source != target) { "A source UUID maps to itself" }
            require(target !in mapping || mapping[target] == source) { "A target UUID is also a source UUID outside a swap" }
        }
        targetMsb = LongArray(targets.size) { targets[it].mostSignificantBits }
        targetLsb = LongArray(targets.size) { targets[it].leastSignificantBits }
    }

    val isEmpty: Boolean get() = sources.isEmpty()

    /** Index of the source equal to ([msb], [lsb]), or -1. */
    fun find(msb: Long, lsb: Long): Int {
        for (i in sourceMsb.indices) {
            if (sourceMsb[i] == msb && sourceLsb[i] == lsb) return i
        }
        return -1
    }

    fun targetMsb(index: Int): Long = targetMsb[index]

    fun targetLsb(index: Int): Long = targetLsb[index]

    /** Matches 16 big-endian bytes at [offset] (an `IntArray[4]` or `LongArray[2]` payload). */
    fun findBinary(src: ByteArray, offset: Int): Int = find(NbtBytes.s8(src, offset), NbtBytes.s8(src, offset + 8))

    /** Writes the target of source [index] as 16 big-endian bytes. */
    fun writeBinary(index: Int, dst: ByteArray, offset: Int) {
        NbtBytes.putS8(dst, offset, targetMsb[index])
        NbtBytes.putS8(dst, offset + 8, targetLsb[index])
    }

    /**
     * Finds every full source UUID written as ASCII text in `src[from until to]` and writes its target
     * into [dst] at the same position, keeping the dash style and letter case. Text is read from [src]
     * only, so [dst] may be a copy that is being patched. Returns the number of replacements, and calls
     * [onReplace] with the source index of each.
     *
     * The rules match these regular expressions, case-insensitively:
     * dashed `(?<![0-9A-Fa-f-])uuid(?![0-9A-Fa-f-])` and dashless `(?<![0-9A-Fa-f])hex32(?![0-9A-Fa-f])`.
     * They are only safe on encodings whose multi-byte sequences never contain ASCII bytes, such as
     * UTF-8 and Java modified UTF-8.
     */
    fun replaceAscii(src: ByteArray, from: Int, to: Int, dst: () -> ByteArray, onReplace: (Int) -> Unit): Int {
        if (isEmpty || to - from < DASHLESS_LENGTH) return 0
        var replaced = 0
        var i = from
        while (i <= to - DASHLESS_LENGTH) {
            val b = src[i].toInt() and 0xff
            if (hexValue(b) < 0 || (i > from && hexValue(src[i - 1].toInt() and 0xff) >= 0)) {
                i++
                continue
            }
            val previousIsDash = i > from && src[i - 1] == DASH
            if (!previousIsDash && i + DASHED_LENGTH <= to && isDashedAt(src, i) &&
                (i + DASHED_LENGTH == to || !isHexOrDash(src[i + DASHED_LENGTH].toInt() and 0xff))
            ) {
                val index = find(parseHex(src, i, true, 0), parseHex(src, i, true, 1))
                if (index >= 0) {
                    writeAscii(index, src, i, true, dst())
                    onReplace(index)
                    replaced++
                    i += DASHED_LENGTH
                    continue
                }
            }
            if (isDashlessAt(src, i) &&
                (i + DASHLESS_LENGTH == to || hexValue(src[i + DASHLESS_LENGTH].toInt() and 0xff) < 0)
            ) {
                val index = find(parseHex(src, i, false, 0), parseHex(src, i, false, 1))
                if (index >= 0) {
                    writeAscii(index, src, i, false, dst())
                    onReplace(index)
                    replaced++
                    i += DASHLESS_LENGTH
                    continue
                }
            }
            i++
        }
        return replaced
    }

    private fun isDashedAt(src: ByteArray, start: Int): Boolean {
        for (k in 0 until DASHED_LENGTH) {
            val b = src[start + k].toInt() and 0xff
            if (k == 8 || k == 13 || k == 18 || k == 23) {
                if (b != DASH.toInt()) return false
            } else if (hexValue(b) < 0) {
                return false
            }
        }
        return true
    }

    private fun isDashlessAt(src: ByteArray, start: Int): Boolean {
        for (k in 0 until DASHLESS_LENGTH) {
            if (hexValue(src[start + k].toInt() and 0xff) < 0) return false
        }
        return true
    }

    /** Parses the most ([half] 0) or least ([half] 1) significant 16 hex digits. */
    private fun parseHex(src: ByteArray, start: Int, dashed: Boolean, half: Int): Long {
        var value = 0L
        var digit = 0
        var k = 0
        while (digit < 32) {
            val b = src[start + k].toInt() and 0xff
            k++
            if (dashed && b == DASH.toInt()) continue
            if (digit / 16 == half) value = (value shl 4) or hexValue(b).toLong()
            digit++
        }
        return value
    }

    private fun writeAscii(index: Int, src: ByteArray, start: Int, dashed: Boolean, dst: ByteArray) {
        val length = if (dashed) DASHED_LENGTH else DASHLESS_LENGTH
        var upper = 0
        var lower = 0
        for (k in 0 until length) {
            when (src[start + k].toInt() and 0xff) {
                in 'A'.code..'F'.code -> upper++
                in 'a'.code..'f'.code -> lower++
            }
        }
        val digits = if (upper > 0 && lower == 0) UPPER_DIGITS else LOWER_DIGITS
        var digit = 0
        var k = 0
        while (digit < 32) {
            if (dashed && (k == 8 || k == 13 || k == 18 || k == 23)) {
                k++
                continue
            }
            val word = if (digit < 16) targetMsb[index] else targetLsb[index]
            val nibble = ((word ushr (60 - 4 * (digit % 16))) and 0xf).toInt()
            dst[start + k] = digits[nibble]
            digit++
            k++
        }
    }

    private fun isHexOrDash(b: Int): Boolean = b == DASH.toInt() || hexValue(b) >= 0

    companion object {
        private const val DASHED_LENGTH = 36
        private const val DASHLESS_LENGTH = 32
        private const val DASH = '-'.code.toByte()
        private val LOWER_DIGITS = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
        private val UPPER_DIGITS = "0123456789ABCDEF".toByteArray(Charsets.US_ASCII)

        fun hexValue(b: Int): Int = when (b) {
            in '0'.code..'9'.code -> b - '0'.code
            in 'a'.code..'f'.code -> b - 'a'.code + 10
            in 'A'.code..'F'.code -> b - 'A'.code + 10
            else -> -1
        }
    }
}
