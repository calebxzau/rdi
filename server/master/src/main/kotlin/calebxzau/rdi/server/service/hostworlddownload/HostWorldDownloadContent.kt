package calebxzau.rdi.server.service.hostworlddownload

import calebxzau.rdi.anvilrw.remap.SaveSyncChunks
import calebxzau.rdi.anvilrw.remap.SaveUuidRemapper

/**
 * Which world files go into a save download. Chunk stores (`region/`, `entities/`, `poi/` of every
 * dimension) are left to the builder, which keeps the sync chunks only. Everything else is included
 * unless listed here, because mods put their data in folders of their own.
 */
object HostWorldDownloadContent {
    const val MAX_FILE_BYTES = 256L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024

    private const val RDI_DATA_PREFIX = "rdi_"
    private val SYNC_CHUNK_LIST = SaveSyncChunks.RELATIVE_PATH.substringAfter('/')
    private val EXCLUDED_ROOT_FILES = setOf("session.lock")
    private const val UUID_TEXT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"

    /** `PlayerDataStorage` writes `<uuid>-<random>.dat` first and renames it. */
    private val PLAYER_DATA_TEMP = Regex("${UUID_TEXT}-\\d+\\.dat")

    /** True for files inside a chunk store; the builder handles those. */
    fun isChunkStore(relativePath: String): Boolean = SaveUuidRemapper.chunkStoreDimension(relativePath) != null

    /** Whether a world file outside the chunk stores is downloaded. [relativePath] uses `/`. */
    fun includesFile(relativePath: String): Boolean {
        if (isChunkStore(relativePath)) return false
        val segments = relativePath.split('/')
        val name = segments.last()
        if (segments.size == 1 && name in EXCLUDED_ROOT_FILES) return false
        if (name.startsWith(".") || name.endsWith("_old") || name.endsWith(".tmp")) return false
        // RDI's own host data (backup token, force PvP) belongs to the host; the sync chunk list is
        // kept so the save can be imported into a host again.
        if (segments.size == 2 && segments[0] == "data" && name.startsWith(RDI_DATA_PREFIX) && name != SYNC_CHUNK_LIST) return false
        if (segments.size == 2 && segments[0] == "playerdata" && PLAYER_DATA_TEMP.matches(name)) return false
        // Distant Horizons LOD caches reach gigabytes and are rebuilt by the client.
        if (name.startsWith("DistantHorizons")) return false
        return true
    }
}

/**
 * The bytes of a single `Range: bytes=<start>-[<end>]` inside a file of [length] bytes, or null to send
 * the whole file. Suffix and multiple ranges are not supported and get the whole file.
 */
internal fun byteRange(header: String?, length: Long): LongRange? {
    val match = header?.trim()?.let { Regex("""bytes=(\d+)-(\d*)""").matchEntire(it) } ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }?.coerceAtMost(length - 1) ?: (length - 1)
    if (start >= length || end < start) return null
    return start..end
}
