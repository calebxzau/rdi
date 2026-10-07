package calebxzau.rdi.anvilrw.remap

import java.nio.file.Path

private const val MAX_DIMENSION_ID_LENGTH = 512

/** Windows device names, which open a device instead of a file even with an extension. */
private val WINDOWS_RESERVED_NAMES = setOf("con", "prn", "aux", "nul") +
    (1..9).flatMap { listOf("com${it}", "lpt${it}") }

/**
 * The folder of [dimensionId] inside the save at [root], as `DimensionType.getStorageFolder` lays it
 * out: the root for the overworld, `DIM-1` for the nether, `DIM1` for the end, and
 * `dimensions/<namespace>/<path>` for any other dimension.
 *
 * Dimension IDs come from untrusted files, and resource location rules alone allow `..` and leading
 * `/` segments. This therefore also rejects empty, `.` and `..` segments, segments ending in `.`
 * (Windows drops the dot), Windows device names, and any result outside [root].
 */
fun dimensionStorageFolder(root: Path, dimensionId: String): Result<Path> = remapResult {
    fun invalid(reason: String): Nothing = throw InvalidDimensionIdException("Invalid dimension ID \"${dimensionId}\": ${reason}")

    if (dimensionId.isEmpty() || dimensionId.length > MAX_DIMENSION_ID_LENGTH) invalid("length")
    val separator = dimensionId.indexOf(':')
    val namespace = if (separator > 0) dimensionId.substring(0, separator) else "minecraft"
    val path = if (separator >= 0) dimensionId.substring(separator + 1) else dimensionId
    if (!namespace.all { it in 'a'..'z' || it in '0'..'9' || it in "_-." }) invalid("namespace characters")
    if (!path.all { it in 'a'..'z' || it in '0'..'9' || it in "_-./" }) invalid("path characters")

    val base = root.normalize()
    val folder = when ("${namespace}:${path}") {
        "minecraft:overworld" -> return@remapResult base
        "minecraft:the_nether" -> return@remapResult base.resolve("DIM-1")
        "minecraft:the_end" -> return@remapResult base.resolve("DIM1")
        else -> {
            val segments = listOf(namespace) + path.split('/')
            for (segment in segments) {
                if (segment.isEmpty()) invalid("empty segment")
                if (segment == "." || segment == "..") invalid("relative segment")
                if (segment.endsWith('.')) invalid("segment ends with a dot")
                if (segment.substringBefore('.') in WINDOWS_RESERVED_NAMES) invalid("reserved name")
            }
            segments.fold(base.resolve("dimensions")) { folder, segment -> folder.resolve(segment) }.normalize()
        }
    }
    if (!folder.startsWith(base.resolve("dimensions")) || folder == base.resolve("dimensions")) invalid("outside the save")
    folder
}
