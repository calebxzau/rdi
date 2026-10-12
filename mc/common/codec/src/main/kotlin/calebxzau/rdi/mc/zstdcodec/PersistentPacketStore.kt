package calebxzau.rdi.mc.zstdcodec

import com.github.luben.zstd.Zstd
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * Disk copies of persistent packets: `<root>/<kind>/<sha1>.bin`, each one Zstd frame of the exact
 * packet bytes. The modification time orders the entries; only the newest [keepPerKind] of a kind stay.
 *
 * Every call blocks on disk I/O, so callers choose the thread. A loaded entry is verified against its
 * hash, and an entry that fails is removed so it is never offered again.
 */
class PersistentPacketStore(private val root: Path, private val keepPerKind: Int) {
    init {
        require(keepPerKind >= 1) { "A persistent packet store keeps at least one entry per kind" }
    }

    /** The newest stored hashes of [kind], newest first, at most [limit]. */
    fun recent(kind: Int, limit: Int): Result<List<PersistentPacketHash>> = runCatching {
        val directory = kindDirectory(kind)
        if (!Files.isDirectory(directory)) return@runCatching emptyList()
        entries(directory).take(limit).map { it.hash }
    }

    /** The verified bytes of one entry, or null when it is absent. */
    fun load(kind: Int, hash: PersistentPacketHash): Result<ByteArray?> = runCatching {
        val file = entryFile(kind, hash)
        val stored = try {
            Files.readAllBytes(file)
        } catch (_: NoSuchFileException) {
            return@runCatching null
        }
        val content = runCatching { decompress(stored) }.getOrElse { error ->
            removeCorrupt(file)
            throw IOException("Persistent packet ${file} is not a readable Zstd frame; removed it", error)
        }
        if (PersistentPacketHash.of(content) != hash) {
            removeCorrupt(file)
            throw IOException("Persistent packet ${file} does not match its hash; removed it")
        }
        content
    }

    /** Stores [content] as the newest entry of its kind, then removes the oldest beyond the limit. */
    fun save(kind: Int, hash: PersistentPacketHash, content: ByteArray): Result<Unit> = runCatching {
        require(PersistentPacketKind.isValid(kind)) { "Invalid persistent packet kind ${kind}" }
        val directory = kindDirectory(kind)
        Files.createDirectories(directory)
        val file = entryFile(kind, hash)
        if (!Files.exists(file)) {
            val temporary = Files.createTempFile(directory, hash.toHex(), TEMPORARY_SUFFIX)
            try {
                Files.write(temporary, Zstd.compress(content, DISK_LEVEL))
                moveIntoPlace(temporary, file)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()))
        prune(directory)
    }

    private fun prune(directory: Path) {
        directory.listDirectoryEntries("*${TEMPORARY_SUFFIX}").forEach { Files.deleteIfExists(it) }
        entries(directory).drop(keepPerKind).forEach { Files.deleteIfExists(it.file) }
    }

    private data class StoredEntry(val file: Path, val hash: PersistentPacketHash, val modified: Long)

    /** Files that are not entries are ignored; an entry removed meanwhile by another thread is skipped. */
    private fun entries(directory: Path): List<StoredEntry> =
        directory.listDirectoryEntries("*.${EXTENSION}")
            .filter { it.isRegularFile() }
            .mapNotNull { file ->
                val hash = PersistentPacketHash.parseHex(file.nameWithoutExtension).getOrNull() ?: return@mapNotNull null
                val modified = try {
                    Files.getLastModifiedTime(file).toMillis()
                } catch (_: NoSuchFileException) {
                    return@mapNotNull null
                }
                StoredEntry(file, hash, modified)
            }
            .sortedByDescending { it.modified }

    private fun decompress(stored: ByteArray): ByteArray {
        val size = Zstd.getFrameContentSize(stored)
        require(size in 1..ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH.toLong()) {
            "Persistent packet frame declares ${size} bytes"
        }
        return Zstd.decompress(stored, size.toInt())
    }

    private fun removeCorrupt(file: Path) {
        runCatching { Files.deleteIfExists(file) }.onFailure { error ->
            LOGGER.log(System.Logger.Level.ERROR, "Failed to remove the corrupt persistent packet ${file}", error)
        }
    }

    private fun moveIntoPlace(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun kindDirectory(kind: Int): Path = root.resolve(PersistentPacketKind.name(kind))

    private fun entryFile(kind: Int, hash: PersistentPacketHash): Path =
        kindDirectory(kind).resolve("${hash.toHex()}.${EXTENSION}")

    private companion object {
        val LOGGER: System.Logger = System.getLogger(PersistentPacketStore::class.java.name)
        const val EXTENSION = "bin"
        const val TEMPORARY_SUFFIX = ".tmp"
        const val DISK_LEVEL = 3
    }
}
