package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** How a local pack was put on this computer. */
@Serializable
enum class LocalModpackSourceKind { Archive, Directory, Empty }

/** Who the player is when a local pack starts. */
@Serializable
enum class LocalPlayAccountMode { Offline, Online }

/** Metadata of a pack installed only on this computer, kept in its version directory. */
@Serializable
data class LocalModpackInfo(
    val id: String,
    val name: String,
    val comment: String? = null,
    val iconUrl: String? = null,
    val mcVersion: McVersion,
    val modLoader: ModLoader,
    val createdAt: Long,
    val source: LocalModpackSourceKind,
    val lastAccountMode: LocalPlayAccountMode? = null,
    val lastOfflineName: String? = null,
)

/** Reads and writes `rdi-local-pack.json` in `versions/local_<uuid>` directories. */
object LocalModpackStore {
    const val DIR_PREFIX = "local_"
    const val INFO_FILE_NAME = "rdi-local-pack.json"
    private const val INSTALLING_SUFFIX = ".installing"

    fun versionId(id: String): String = "$DIR_PREFIX$id"

    fun isLocalVersionId(versionId: String): Boolean =
        versionId.startsWith(DIR_PREFIX) && !versionId.endsWith(INSTALLING_SUFFIX)

    fun stagingDirName(id: String): String = "${versionId(id)}$INSTALLING_SUFFIX"

    fun read(versionDir: File): Result<LocalModpackInfo> = runCatching {
        val file = versionDir.resolve(INFO_FILE_NAME)
        require(file.isFile) { "缺少本地整合包信息: ${file.absolutePath}" }
        serdesJson.decodeFromString<LocalModpackInfo>(file.readText())
    }

    fun write(versionDir: File, info: LocalModpackInfo): Result<Unit> = runCatching {
        val target = versionDir.resolve(INFO_FILE_NAME).toPath()
        val temporary = versionDir.resolve(".$INFO_FILE_NAME.tmp").toPath()
        Files.writeString(temporary, serdesJson.encodeToString(info))
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun update(versionDir: File, transform: (LocalModpackInfo) -> LocalModpackInfo): Result<LocalModpackInfo> =
        read(versionDir).mapCatching { current ->
            transform(current).also { write(versionDir, it).getOrThrow() }
        }

    /** Local pack directories under [versionsDir]; unreadable ones are reported, not hidden. */
    fun scan(versionsDir: File): List<Pair<File, Result<LocalModpackInfo>>> =
        versionsDir.listFiles()
            ?.filter { it.isDirectory && isLocalVersionId(it.name) }
            ?.sortedBy { it.name }
            ?.map { it to read(it) }
            .orEmpty()

    /** Appends " (2)", " (3)" … until [name] no longer clashes with [existingNames], ignoring case. */
    fun uniqueName(name: String, existingNames: Collection<String>): String {
        val taken = existingNames.mapTo(hashSetOf()) { it.trim().lowercase() }
        val base = name.trim()
        if (base.lowercase() !in taken) return base
        var index = 2
        while ("$base ($index)".lowercase() in taken) index++
        return "$base ($index)"
    }

    /** Removes `local_*.installing` leftovers of an interrupted install. */
    fun deleteStaleStagingDirs(versionsDir: File): Result<Unit> = runCatching {
        versionsDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(DIR_PREFIX) && it.name.endsWith(INSTALLING_SUFFIX) }
            ?.forEach { it.deleteRecursivelyNoSymlink() }
    }
}
