package calebxzau.rdi.anvilrw.remap

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Output of [TextUuidPatcher.patch]. [bytes] is the input array itself when nothing [changed]. */
class TextPatchResult(
    val bytes: ByteArray,
    val changed: Boolean,
    val replacements: Map<UUID, Int>,
    /** `XxxMost: …L` / `XxxLeast: …L` pairs equal to a source UUID. They are detected, never replaced. */
    val mostLeastHits: Int,
)

/**
 * Replaces mapped player UUIDs in strictly valid UTF-8 text (SNBT, JSON, TOML, config files…).
 *
 * The text is handled as raw bytes: in UTF-8 every byte of a multi-byte character is `>= 0x80`, so an
 * ASCII match can never start or end inside a character, and ASCII replacements keep the file valid
 * UTF-8. Only matched ranges change; line endings, BOM and everything else stay byte-for-byte.
 *
 * - Dashed and dashless UUID text: same length, keeping dash style and letter case.
 * - SNBT `[I; a, b, c, d]` and JSON `[a, b, c, d]` int arrays, only when all four ints equal a source
 *   UUID. The whitespace and separators are kept; the numbers may change length.
 * - `Most`/`Least` long pairs are only counted in [TextPatchResult.mostLeastHits].
 */
class TextUuidPatcher(mapping: Map<UUID, UUID>) {
    private val matcher = UuidMatcher(mapping)

    fun patch(text: ByteArray): Result<TextPatchResult> = remapResult {
        if (!isStrictUtf8(text)) throw NotUtf8TextException("Text is not strictly valid UTF-8 or contains NUL bytes")
        val counts = IntArray(matcher.sources.size)
        var out: ByteArray? = null
        matcher.replaceAscii(text, 0, text.size, { out ?: text.copyOf().also { out = it } }) { counts[it]++ }

        // Positions in `view` equal byte offsets in `text` (ISO-8859-1 maps one byte to one char).
        val view = String(text, Charsets.ISO_8859_1)
        val arrays = INT_ARRAY.findAll(view).mapNotNull { match ->
            val ints = (2..5).map { match.groups[it]!!.value.toIntOrNull() ?: return@mapNotNull null }
            val msb = (ints[0].toLong() shl 32) or (ints[1].toLong() and 0xffffffffL)
            val lsb = (ints[2].toLong() shl 32) or (ints[3].toLong() and 0xffffffffL)
            val index = matcher.find(msb, lsb)
            if (index < 0) null else match to index
        }.toList()
        val mostLeastHits = countMostLeastHits(view)

        val asciiPatched = out ?: text
        val bytes = if (arrays.isEmpty()) {
            asciiPatched
        } else {
            val builder = java.io.ByteArrayOutputStream(text.size + 64)
            var position = 0
            for ((match, index) in arrays) {
                counts[index]++
                val msb = matcher.targetMsb(index)
                val lsb = matcher.targetLsb(index)
                val values = listOf((msb ushr 32).toInt(), msb.toInt(), (lsb ushr 32).toInt(), lsb.toInt())
                for (group in 2..5) {
                    val range = match.groups[group]!!.range
                    builder.write(asciiPatched, position, range.first - position)
                    builder.write(values[group - 2].toString().toByteArray(Charsets.US_ASCII))
                    position = range.last + 1
                }
            }
            builder.write(asciiPatched, position, asciiPatched.size - position)
            builder.toByteArray()
        }
        val replacements = buildMap {
            counts.forEachIndexed { index, value -> if (value > 0) put(matcher.sources[index], value) }
        }
        TextPatchResult(bytes, bytes !== text, replacements, mostLeastHits)
    }

    private fun countMostLeastHits(view: String): Int {
        val most = HashMap<String, MutableList<Long>>()
        val least = HashMap<String, MutableList<Long>>()
        for (match in MOST_LEAST.findAll(view)) {
            val value = match.groupValues[3].toLongOrNull() ?: continue
            val side = if (match.groupValues[2] == "Most") most else least
            side.getOrPut(match.groupValues[1]) { ArrayList() } += value
        }
        var hits = 0
        for ((prefix, msbs) in most) {
            val lsbs = least[prefix] ?: continue
            for (msb in msbs) for (lsb in lsbs) if (matcher.find(msb, lsb) >= 0) hits++
        }
        return hits
    }

    companion object {
        private val INT_ARRAY = Regex("""\[(I;)?\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*]""")
        /** SNBT `UUIDMost: 123L`, or JSON `"UUIDMost": 123`. */
        private val MOST_LEAST = Regex("""([A-Za-z0-9_]*)(Most|Least)"?\s*:\s*(-?\d+)[Ll]?""")

        /** True if [bytes] decode as UTF-8 with errors reported, and contain no NUL byte. */
        fun isStrictUtf8(bytes: ByteArray): Boolean {
            if (bytes.any { it == 0.toByte() }) return false
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                true
            } catch (_: CharacterCodingException) {
                false
            }
        }
    }
}
