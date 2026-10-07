package calebxzau.rdi.anvilrw.remap

import java.util.UUID

/**
 * Renames path segments whose stem (the part before the first `.`) is exactly a mapped player UUID,
 * dashed or dashless and in any letter case. The style, case and extension are kept, so
 * `playerdata/<a>.dat_old` becomes `playerdata/<b>.dat_old` and `saved_players/<a>/` becomes
 * `saved_players/<b>/`. A UUID that is only part of a stem is never renamed.
 *
 * This class does not check for conflicts: the caller compares the renamed paths of a whole tree.
 */
class SavePathRenamer(mapping: Map<UUID, UUID>) {
    private val matcher = UuidMatcher(mapping)

    /** Renames each segment of a `/`-separated relative path. */
    fun rename(relativePath: String): String =
        relativePath.split('/').joinToString("/") { renameSegment(it) }

    fun renameSegment(segment: String): String {
        val stemLength = segment.indexOf('.').let { if (it < 0) segment.length else it }
        val stem = segment.substring(0, stemLength)
        if (!DASHED.matches(stem) && !DASHLESS.matches(stem)) return segment
        val bytes = stem.toByteArray(Charsets.US_ASCII)
        var out: ByteArray? = null
        val replaced = matcher.replaceAscii(bytes, 0, stemLength, { out ?: bytes.copyOf().also { out = it } }) {}
        val renamed = out
        return if (replaced == 1 && renamed != null) String(renamed, Charsets.US_ASCII) + segment.substring(stemLength) else segment
    }

    private companion object {
        val DASHED = Regex("""[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}""")
        val DASHLESS = Regex("""[0-9A-Fa-f]{32}""")
    }
}
