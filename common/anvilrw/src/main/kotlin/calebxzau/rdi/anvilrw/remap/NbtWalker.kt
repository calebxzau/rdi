package calebxzau.rdi.anvilrw.remap

/** NBT tag type ids. */
internal object NbtTag {
    const val END = 0
    const val BYTE = 1
    const val SHORT = 2
    const val INT = 3
    const val LONG = 4
    const val FLOAT = 5
    const val DOUBLE = 6
    const val BYTE_ARRAY = 7
    const val STRING = 8
    const val LIST = 9
    const val COMPOUND = 10
    const val INT_ARRAY = 11
    const val LONG_ARRAY = 12

    /** Payload size of fixed-size tags, or 0 for variable-size tags. */
    fun fixedSize(type: Int): Int = when (type) {
        BYTE -> 1
        SHORT -> 2
        INT, FLOAT -> 4
        LONG, DOUBLE -> 8
        else -> 0
    }
}

/** Callbacks for [NbtWalker]. Offsets are absolute offsets into the walked array. */
internal interface NbtVisitor {
    fun beginCompound() {}

    /** A named entry of the current compound; called before its payload is walked. */
    fun compoundEntry(type: Int, nameOffset: Int, nameLength: Int, payloadOffset: Int) {}

    fun endCompound() {}

    /** The bytes of a `TAG_String` value (modified UTF-8, without the length prefix). */
    fun stringPayload(offset: Int, length: Int) {}

    /** The big-endian elements of a `TAG_Int_Array`. */
    fun intArrayPayload(offset: Int, count: Int) {}

    /** The big-endian elements of a `TAG_Long_Array`. */
    fun longArrayPayload(offset: Int, count: Int) {}
}

/**
 * A strict, read-only structural walker over binary NBT.
 *
 * It never allocates from a declared length: every length is checked against the remaining input
 * before the walker moves past it, so a tiny input that declares a huge array or list fails at once.
 * It follows Minecraft's reader rules for depth (root compound at depth 0, at most
 * [RemapLimits.NBT_MAX_DEPTH]) and rejects a `TAG_End` list with a non-zero count. String contents
 * are not decoded.
 */
internal class NbtWalker(
    private val src: ByteArray,
    private val start: Int = 0,
    private val end: Int = src.size,
    private val maxDepth: Int = RemapLimits.NBT_MAX_DEPTH,
) {
    init {
        require(start in 0..end && end <= src.size) { "Invalid range ${start}..${end} for ${src.size} bytes" }
    }

    /**
     * Walks a complete document: a named root compound that must end exactly at [end].
     * Returns the offset of the root compound's payload.
     */
    fun walkDocument(visitor: NbtVisitor?): Int {
        val type = u1(start)
        if (type != NbtTag.COMPOUND) fail("Root tag must be a compound, got type ${type}", start)
        val nameLength = u2(start + 1)
        val payload = advance(start + 3, nameLength.toLong())
        val after = walkPayload(NbtTag.COMPOUND, payload, 0, visitor)
        if (after != end) fail("${end - after} trailing bytes after the root tag", after)
        return payload
    }

    /** Walks one payload of [type] at [offset] and returns the offset just after it. */
    fun walkPayload(type: Int, offset: Int, depth: Int, visitor: NbtVisitor?): Int =
        when (type) {
            NbtTag.BYTE, NbtTag.SHORT, NbtTag.INT, NbtTag.LONG, NbtTag.FLOAT, NbtTag.DOUBLE ->
                advance(offset, NbtTag.fixedSize(type).toLong())
            NbtTag.BYTE_ARRAY -> advance(offset + 4, arrayCount(offset).toLong())
            NbtTag.STRING -> {
                val length = u2(offset)
                val after = advance(offset + 2, length.toLong())
                visitor?.stringPayload(offset + 2, length)
                after
            }
            NbtTag.INT_ARRAY -> {
                val count = arrayCount(offset)
                val after = advance(offset + 4, count * 4L)
                visitor?.intArrayPayload(offset + 4, count)
                after
            }
            NbtTag.LONG_ARRAY -> {
                val count = arrayCount(offset)
                val after = advance(offset + 4, count * 8L)
                visitor?.longArrayPayload(offset + 4, count)
                after
            }
            NbtTag.LIST -> walkList(offset, depth, visitor)
            NbtTag.COMPOUND -> walkCompound(offset, depth, visitor)
            else -> fail("Unknown tag type ${type}", offset)
        }

    private fun walkCompound(offset: Int, depth: Int, visitor: NbtVisitor?): Int {
        if (depth > maxDepth) fail("NBT nesting is deeper than ${maxDepth}", offset)
        visitor?.beginCompound()
        var position = offset
        while (true) {
            val type = u1(position)
            position += 1
            if (type == NbtTag.END) break
            if (type > NbtTag.LONG_ARRAY) fail("Unknown tag type ${type}", position - 1)
            val nameLength = u2(position)
            val nameOffset = position + 2
            position = advance(nameOffset, nameLength.toLong())
            visitor?.compoundEntry(type, nameOffset, nameLength, position)
            position = walkPayload(type, position, depth + 1, visitor)
        }
        visitor?.endCompound()
        return position
    }

    private fun walkList(offset: Int, depth: Int, visitor: NbtVisitor?): Int {
        if (depth > maxDepth) fail("NBT nesting is deeper than ${maxDepth}", offset)
        val elementType = u1(offset)
        val count = s4(offset + 1)
        if (count < 0) fail("Negative list length ${count}", offset + 1)
        var position = offset + 5
        if (elementType == NbtTag.END) {
            if (count > 0) fail("List of TAG_End declares ${count} elements", offset)
            return position
        }
        if (elementType > NbtTag.LONG_ARRAY) fail("Unknown list element type ${elementType}", offset)
        // Every element takes at least one byte.
        if (count > end - position) fail("List declares ${count} elements but only ${end - position} bytes remain", offset)
        val fixed = NbtTag.fixedSize(elementType)
        if (fixed > 0) return advance(position, count.toLong() * fixed)
        repeat(count) {
            position = walkPayload(elementType, position, depth + 1, visitor)
        }
        return position
    }

    private fun arrayCount(offset: Int): Int {
        val count = s4(offset)
        if (count < 0) fail("Negative array length ${count}", offset)
        return count
    }

    private fun need(offset: Int, length: Long) {
        if (length < 0 || offset.toLong() + length > end) {
            fail("Truncated NBT: ${length} bytes needed but ${end - offset} remain", offset)
        }
    }

    private fun advance(offset: Int, length: Long): Int {
        need(offset, length)
        return (offset + length).toInt()
    }

    private fun u1(offset: Int): Int {
        need(offset, 1)
        return src[offset].toInt() and 0xff
    }

    private fun u2(offset: Int): Int {
        need(offset, 2)
        return NbtBytes.u2(src, offset)
    }

    private fun s4(offset: Int): Int {
        need(offset, 4)
        return NbtBytes.s4(src, offset)
    }

    private fun fail(message: String, offset: Int): Nothing = throw NbtFormatException(message, offset)
}

/** Big-endian primitive access shared by the walker, patcher and metadata reader. */
internal object NbtBytes {
    fun u2(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xff) shl 8) or (src[offset + 1].toInt() and 0xff)

    fun s4(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xff) shl 24) or
            ((src[offset + 1].toInt() and 0xff) shl 16) or
            ((src[offset + 2].toInt() and 0xff) shl 8) or
            (src[offset + 3].toInt() and 0xff)

    fun s8(src: ByteArray, offset: Int): Long =
        (s4(src, offset).toLong() shl 32) or (s4(src, offset + 4).toLong() and 0xffffffffL)

    fun putS8(dst: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            dst[offset + i] = (value ushr (56 - 8 * i)).toByte()
        }
    }
}
