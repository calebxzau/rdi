package calebxzau.rdi.mc.syncchunk.backup

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.streams.asSequence

/**
 * World files backed up next to the sync chunks: player data and the world data that holds items
 * outside chunks (e.g. Sophisticated Backpacks contents). Paths are relative to the world directory.
 */
object SyncChunkBackupFileRules {
    val ROOTS = listOf("data", "ftbquests", "ftbessentials", "stats", "advancements", "playerdata")

    /** Same-named files under `data/` hold world-wide state (MineColonies colonies, raids, scoreboards). */
    private val EXCLUDED_DATA_FILES = setOf("capabilities.dat", "raids.dat", "scoreboard.dat")

    /** RDI's own world data (sync chunk list, force PvP, backup token) is never rolled back. */
    private const val RDI_DATA_PREFIX = "rdi_"

    const val MAX_FILE_BYTES = 128L * 1024 * 1024

    private val UUID_TEXT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    private val PLAYER_DATA = Regex("playerdata/(${UUID_TEXT})\\.dat")

    /** `PlayerDataStorage` writes `<uuid>-<random>.dat` first and renames it. */
    private val PLAYER_DATA_TEMP = Regex("${UUID_TEXT}-\\d+\\.dat")

    /** NeoForge writes saved data to `<name><random>.neoforge-tmp` on an IO thread and renames it. */
    private const val NEOFORGE_TEMP_SUFFIX = ".neoforge-tmp"

    fun isSafeRelativePath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || '\\' in path || ':' in path) return false
        val segments = path.split('/')
        return segments.size >= 2 && segments.first() in ROOTS && segments.none { it.isEmpty() || it == "." || it == ".." }
    }

    fun includes(path: String): Boolean {
        if (!isSafeRelativePath(path)) return false
        val segments = path.split('/')
        val name = segments.last()
        if (name.startsWith(".") || name.endsWith(".dat_old") || name.endsWith(".tmp") || name.endsWith(NEOFORGE_TEMP_SUFFIX)) return false
        if (segments.first() == "data" && segments.size == 2) {
            if (name.startsWith(RDI_DATA_PREFIX) || name in EXCLUDED_DATA_FILES) return false
        }
        if (segments.first() == "playerdata" && PLAYER_DATA_TEMP.matches(name)) return false
        return true
    }

    /** The player UUID of `playerdata/<uuid>.dat`, used to count players; null for every other path. */
    fun playerDataUuid(path: String): String? = PLAYER_DATA.matchEntire(path)?.groupValues?.get(1)

    /** Lists matching regular files, sorted; symbolic links are never followed. */
    fun collect(worldDir: Path): Result<List<String>> = runCatching {
        ROOTS.flatMap { root ->
            val dir = worldDir.resolve(root)
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return@flatMap emptyList()
            Files.walk(dir).use { stream ->
                stream.asSequence()
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .map { worldDir.relativize(it).invariantSeparatorsPathString }
                    .filter(::includes)
                    .toList()
            }
        }.sorted()
    }

    /** Reads [file] and fails if its size or modification time changed meanwhile (a mod was writing it). */
    fun readStable(file: Path, maxBytes: Long = MAX_FILE_BYTES): Result<ByteArray> = runCatching {
        val before = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!before.isRegularFile) throw IOException("${file}不是普通文件")
        if (before.size() > maxBytes) throw IOException("${file}超过备份大小上限${maxBytes}字节")
        val bytes = Files.readAllBytes(file)
        val after = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (after.size() != before.size() || after.lastModifiedTime() != before.lastModifiedTime() || bytes.size.toLong() != after.size()) {
            throw IOException("${file}在读取时被修改")
        }
        bytes
    }
}
