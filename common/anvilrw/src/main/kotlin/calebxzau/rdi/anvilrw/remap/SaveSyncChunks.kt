package calebxzau.rdi.anvilrw.remap

import java.nio.file.Path
import java.util.UUID

/** One marked chunk. [dimensionId] is canonical: `minecraft:` is added when the namespace is missing. */
data class SaveSyncChunk(val dimensionId: String, val chunkX: Int, val chunkZ: Int, val owner: UUID)

/**
 * Reads `data/rdi_sync_chunks.dat` with the same rules as `SyncChunkSaveFormat.read`, without
 * Minecraft classes: gzip NBT whose `data.chunks` list holds `{dimension, x, z, owner}` compounds.
 * The whole file is rejected if any entry is invalid, duplicated, or there are too many entries.
 * Dimension IDs must also be safe as save folders ([dimensionStorageFolder]).
 */
object SaveSyncChunks {
    const val RELATIVE_PATH = "data/rdi_sync_chunks.dat"
    const val MAX_ENTRIES = 256

    private val DASHED_UUID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

    fun parse(gzipped: ByteArray): Result<List<SaveSyncChunk>> = remapResult {
        if (gzipped.size > RemapLimits.STRICT_NBT_FILE_BYTES) throw RemapLimitExceededException("${RELATIVE_PATH} exceeds its size limit")
        val nbt = BoundedIo.gunzip(gzipped, RemapLimits.STRICT_NBT_FILE_BYTES, RELATIVE_PATH)
        val reader = NbtMetadataReader.open(nbt).getOrElse { throw SyncChunkListException("${RELATIVE_PATH} is not valid NBT", it) }
        val list = reader.root.child("data")?.child("chunks")?.takeIf { it.type == NbtTag.LIST }
            ?: throw SyncChunkListException("${RELATIVE_PATH} has no data.chunks list")
        val elements = list.listElements().orEmpty()
        if (elements.size > MAX_ENTRIES) throw SyncChunkListException("${RELATIVE_PATH} has ${elements.size} entries, more than ${MAX_ENTRIES}")
        val seen = HashSet<Triple<String, Int, Int>>()
        elements.mapIndexed { index, element ->
            fun invalid(reason: String): Nothing = throw SyncChunkListException("${RELATIVE_PATH} entry ${index + 1}: ${reason}")
            if (element.type != NbtTag.COMPOUND) invalid("not a compound")
            val dimension = element.child("dimension")?.stringOrNull() ?: invalid("missing dimension")
            val x = element.child("x")?.intOrNull() ?: invalid("missing x")
            val z = element.child("z")?.intOrNull() ?: invalid("missing z")
            val ownerText = element.child("owner")?.stringOrNull() ?: invalid("missing owner")
            if (!DASHED_UUID.matches(ownerText)) invalid("invalid owner ${ownerText}")
            dimensionStorageFolder(Path.of("world"), dimension).getOrElse { invalid(it.message ?: "invalid dimension") }
            val canonical = canonicalDimensionId(dimension)
            if (!seen.add(Triple(canonical, x, z))) invalid("duplicate chunk ${canonical} ${x},${z}")
            SaveSyncChunk(canonical, x, z, UUID.fromString(ownerText))
        }
    }

    /** `overworld` and `:overworld` become `minecraft:overworld`, as Minecraft's resource locations do. */
    fun canonicalDimensionId(dimensionId: String): String {
        val separator = dimensionId.indexOf(':')
        return when {
            separator < 0 -> "minecraft:${dimensionId}"
            separator == 0 -> "minecraft${dimensionId}"
            else -> dimensionId
        }
    }
}
