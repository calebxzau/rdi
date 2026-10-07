package calebxzau.rdi.anvilrw.remap

import java.util.UUID

/** Output of [NbtBytePatcher.patch]. [bytes] is the input array itself when nothing [changed]. */
class NbtPatchResult(
    val bytes: ByteArray,
    val changed: Boolean,
    val replacements: Map<UUID, Int>,
)

/**
 * Replaces mapped player UUIDs inside binary NBT without decoding or re-encoding anything.
 *
 * Every replacement preserves length (16-byte `IntArray[4]`/`LongArray[2]`, `Most`/`Least` long
 * pairs, and 36/32-character ASCII UUIDs in strings and compound keys), so the output is the input
 * with only the matched bytes changed. Strings are matched on their raw modified UTF-8 bytes: bytes
 * below `0x80` never occur inside a multi-byte sequence, so an ASCII UUID can be replaced safely
 * without decoding the string.
 *
 * The whole document is validated structurally (see [NbtWalker]) whether or not anything matches.
 */
class NbtBytePatcher(mapping: Map<UUID, UUID>) {
    private val matcher = UuidMatcher(mapping)

    /** Validates [nbt] as a complete document and replaces every mapped UUID in it. */
    fun patch(nbt: ByteArray): Result<NbtPatchResult> = remapResult {
        if (matcher.isEmpty) {
            NbtWalker(nbt).walkDocument(null)
            NbtPatchResult(nbt, false, emptyMap())
        } else {
            val visitor = PatchVisitor(nbt, matcher)
            NbtWalker(nbt).walkDocument(visitor)
            visitor.result()
        }
    }

    companion object {
        /** Checks that [nbt] is a structurally valid document, without matching anything. */
        fun validate(nbt: ByteArray): Result<Unit> = remapResult<Unit> {
            NbtWalker(nbt).walkDocument(null)
        }
    }
}

private class PatchVisitor(
    private val src: ByteArray,
    private val matcher: UuidMatcher,
) : NbtVisitor {
    private var out: ByteArray? = null
    private val counts = IntArray(matcher.sources.size)
    private val frames = ArrayList<CompoundFrame>()
    private var depth = -1

    private val output: () -> ByteArray = { out ?: src.copyOf().also { out = it } }
    private val count: (Int) -> Unit = { counts[it]++ }

    fun result(): NbtPatchResult {
        val patched = out
        val replacements = buildMap {
            counts.forEachIndexed { index, value -> if (value > 0) put(matcher.sources[index], value) }
        }
        return if (patched == null) {
            NbtPatchResult(src, false, replacements)
        } else {
            check(patched.size == src.size) { "Patched NBT changed length" }
            NbtPatchResult(patched, true, replacements)
        }
    }

    override fun beginCompound() {
        depth++
        if (depth == frames.size) frames += CompoundFrame()
        frames[depth].reset()
    }

    override fun compoundEntry(type: Int, nameOffset: Int, nameLength: Int, payloadOffset: Int) {
        val frame = frames[depth]
        frame.addKey(nameOffset, nameLength)
        if (matcher.replaceAscii(src, nameOffset, nameOffset + nameLength, output, count) > 0) {
            frame.renamed = true
        }
        if (type == NbtTag.LONG) {
            when {
                endsWith(nameOffset, nameLength, MOST) -> frame.addMost(nameOffset, nameLength - MOST.size, payloadOffset)
                endsWith(nameOffset, nameLength, LEAST) -> frame.addLeast(nameOffset, nameLength - LEAST.size, payloadOffset)
            }
        }
    }

    override fun endCompound() {
        val frame = frames[depth]
        if (frame.renamed) checkKeyConflicts(frame)
        pairMostLeast(frame)
        depth--
    }

    override fun stringPayload(offset: Int, length: Int) {
        matcher.replaceAscii(src, offset, offset + length, output, count)
    }

    override fun intArrayPayload(offset: Int, count: Int) {
        if (count == 4) replaceBinary(offset)
    }

    override fun longArrayPayload(offset: Int, count: Int) {
        if (count == 2) replaceBinary(offset)
    }

    private fun replaceBinary(offset: Int) {
        val index = matcher.findBinary(src, offset)
        if (index >= 0) {
            matcher.writeBinary(index, output(), offset)
            counts[index]++
        }
    }

    private fun pairMostLeast(frame: CompoundFrame) {
        for (m in 0 until frame.mostCount) {
            for (l in 0 until frame.leastCount) {
                if (!samePrefix(frame.mostPrefix[m], frame.mostPrefixLength[m], frame.leastPrefix[l], frame.leastPrefixLength[l])) continue
                val mostOffset = frame.mostPayload[m]
                val leastOffset = frame.leastPayload[l]
                val index = matcher.find(NbtBytes.s8(src, mostOffset), NbtBytes.s8(src, leastOffset))
                if (index >= 0) {
                    val dst = output()
                    NbtBytes.putS8(dst, mostOffset, matcher.targetMsb(index))
                    NbtBytes.putS8(dst, leastOffset, matcher.targetLsb(index))
                    counts[index]++
                }
            }
        }
    }

    /** After a key rename, the compound's keys (as patched) must still be unique. */
    private fun checkKeyConflicts(frame: CompoundFrame) {
        val current = out ?: src
        val seen = HashSet<String>(frame.keyCount * 2)
        for (k in 0 until frame.keyCount) {
            val key = String(current, frame.keyOffset[k], frame.keyLength[k], Charsets.ISO_8859_1)
            if (!seen.add(key)) {
                throw NbtKeyConflictException("Renaming a UUID key would duplicate key '${key}' in the same compound")
            }
        }
    }

    private fun endsWith(offset: Int, length: Int, suffix: ByteArray): Boolean {
        if (length < suffix.size) return false
        val start = offset + length - suffix.size
        for (i in suffix.indices) {
            if (src[start + i] != suffix[i]) return false
        }
        return true
    }

    private fun samePrefix(a: Int, aLength: Int, b: Int, bLength: Int): Boolean {
        if (aLength != bLength) return false
        for (i in 0 until aLength) {
            if (src[a + i] != src[b + i]) return false
        }
        return true
    }

    private companion object {
        val MOST = "Most".toByteArray(Charsets.US_ASCII)
        val LEAST = "Least".toByteArray(Charsets.US_ASCII)
    }
}

/** Per-compound state, reused across compounds at the same depth to avoid allocation. */
private class CompoundFrame {
    var renamed = false
    var keyCount = 0
    var keyOffset = IntArray(8)
    var keyLength = IntArray(8)
    var mostCount = 0
    var mostPrefix = IntArray(2)
    var mostPrefixLength = IntArray(2)
    var mostPayload = IntArray(2)
    var leastCount = 0
    var leastPrefix = IntArray(2)
    var leastPrefixLength = IntArray(2)
    var leastPayload = IntArray(2)

    fun reset() {
        renamed = false
        keyCount = 0
        mostCount = 0
        leastCount = 0
    }

    fun addKey(offset: Int, length: Int) {
        if (keyCount == keyOffset.size) {
            keyOffset = keyOffset.copyOf(keyCount * 2)
            keyLength = keyLength.copyOf(keyCount * 2)
        }
        keyOffset[keyCount] = offset
        keyLength[keyCount] = length
        keyCount++
    }

    fun addMost(prefix: Int, prefixLength: Int, payload: Int) {
        if (mostCount == mostPrefix.size) {
            mostPrefix = mostPrefix.copyOf(mostCount * 2)
            mostPrefixLength = mostPrefixLength.copyOf(mostCount * 2)
            mostPayload = mostPayload.copyOf(mostCount * 2)
        }
        mostPrefix[mostCount] = prefix
        mostPrefixLength[mostCount] = prefixLength
        mostPayload[mostCount] = payload
        mostCount++
    }

    fun addLeast(prefix: Int, prefixLength: Int, payload: Int) {
        if (leastCount == leastPrefix.size) {
            leastPrefix = leastPrefix.copyOf(leastCount * 2)
            leastPrefixLength = leastPrefixLength.copyOf(leastCount * 2)
            leastPayload = leastPayload.copyOf(leastCount * 2)
        }
        leastPrefix[leastCount] = prefix
        leastPrefixLength[leastCount] = prefixLength
        leastPayload[leastCount] = payload
        leastCount++
    }
}
