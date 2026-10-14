package calebxzau.rdi.server.service.hostworlddownload

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name

/** One build that used up one of the day's downloads. */
@Serializable
data class HostWorldDownloadRecord(
    val id: String,
    val playerId: String,
    /** Asia/Shanghai date, e.g. `2026-10-13`. */
    val day: String,
    val startedAt: Long,
    val finishedAt: Long,
    val bytes: Long,
    val sha1: String,
    val chunkCount: Int,
)

@Serializable
data class HostWorldDownloadRecords(
    val version: Int = 1,
    val records: List<HostWorldDownloadRecord> = emptyList(),
)

/** The packed save that can still be downloaded, and the world it was packed from. */
@Serializable
data class HostWorldDownloadArchive(
    val id: String,
    val bytes: Long,
    val sha1: String,
    val chunkCount: Int,
    val builtAt: Long,
    val world: HostWorldStamp,
)

/**
 * `level.dat` is rewritten whenever the server saves or stops, and replaced by a reset or an import,
 * so an unchanged stamp means the world on disk is still the one that was packed.
 */
@Serializable
data class HostWorldStamp(val levelDatSize: Long, val levelDatModified: Long) {
    companion object {
        /** Null when the world has no `level.dat` yet. */
        fun of(worldDir: Path): Result<HostWorldStamp?> = runCatching {
            val levelDat = worldDir.resolve("level.dat")
            if (!Files.exists(levelDat, LinkOption.NOFOLLOW_LINKS)) return@runCatching null
            val attributes = Files.readAttributes(levelDat, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attributes.isRegularFile) return@runCatching null
            HostWorldStamp(attributes.size(), attributes.lastModifiedTime().toMillis())
        }
    }
}

/**
 * Files of the save download in `<host>/rdi/world-download/`, next to `rdi/backup`. Players cannot
 * reach `rdi/` through the host file manager. All writes replace files atomically; callers serialize
 * them per host.
 */
object HostWorldDownloadFiles {
    const val RECORDS = "records.json"
    const val ARCHIVE = "current.json"
    private const val RECORD_DAYS = 30L

    private val logger = KotlinLogging.logger {}
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun rootOf(hostDir: Path): Path = hostDir.resolve("rdi").resolve("world-download")

    fun archiveFile(root: Path, id: String): Path = root.resolve("${id}.tar.zst")

    /**
     * A damaged records file is kept aside as `records.json.broken-<time>` and the host starts over,
     * so one bad write cannot block its downloads for good; that day's count may restart once.
     */
    fun readRecords(root: Path, now: Long = System.currentTimeMillis()): Result<HostWorldDownloadRecords> = runCatching {
        val file = root.resolve(RECORDS)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return@runCatching HostWorldDownloadRecords()
        runCatching { json.decodeFromString<HostWorldDownloadRecords>(Files.readString(file)) }.getOrElse { exception ->
            val broken = file.resolveSibling("${RECORDS}.broken-${now}")
            logger.error(exception) { "存档下载记录无法读取，已移到${broken}" }
            Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING)
            HostWorldDownloadRecords()
        }
    }

    /** Appends [record] and drops records older than 30 days. */
    fun appendRecord(root: Path, records: HostWorldDownloadRecords, record: HostWorldDownloadRecord): Result<HostWorldDownloadRecords> = runCatching {
        val oldest = record.finishedAt - RECORD_DAYS * 24 * 3600 * 1000
        val updated = records.copy(records = records.records.filter { it.finishedAt >= oldest } + record)
        write(root.resolve(RECORDS), json.encodeToString(updated)).getOrThrow()
        updated
    }

    fun readArchive(root: Path): Result<HostWorldDownloadArchive?> = runCatching {
        val file = root.resolve(ARCHIVE)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return@runCatching null
        json.decodeFromString<HostWorldDownloadArchive>(Files.readString(file))
    }

    fun writeArchive(root: Path, archive: HostWorldDownloadArchive): Result<Unit> =
        write(root.resolve(ARCHIVE), json.encodeToString(archive))

    /** Removes the packed save and leftovers of interrupted builds; the records stay. */
    fun discardArchives(root: Path): Result<Unit> = runCatching {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return@runCatching
        Files.deleteIfExists(root.resolve(ARCHIVE))
        Files.list(root).use { stream ->
            stream.filter { it.name.endsWith(".tar.zst") || it.name.startsWith(".work-") || it.name.startsWith(".build-") }
                .toList()
        }.forEach { it.toFile().deleteRecursively() }
    }

    private fun write(file: Path, text: String): Result<Unit> = runCatching {
        Files.createDirectories(file.parent)
        val tmp = Files.createTempFile(file.parent, ".${file.name}-", ".tmp")
        try {
            Files.writeString(tmp, text)
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
